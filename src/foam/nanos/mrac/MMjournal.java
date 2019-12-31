/**
 * @license
 * Copyright 2020 The FOAM Authors. All Rights Reserved.
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package foam.nanos.mrac;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Queue;
import java.util.HashMap;
import java.util.Comparator;
import java.util.Iterator;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import foam.core.X;
import foam.core.FObject;
import foam.core.FoamThread;
import foam.core.AbstractFObject;
import foam.dao.DAO;
import foam.dao.Journal;
import foam.dao.AbstractJournal;
import static foam.mlang.MLang.*;
import foam.dao.Sink;
import foam.dao.ArraySink;
import foam.dao.AbstractSink;
import foam.mlang.sink.GroupBy;
import foam.core.Identifiable;
import foam.box.Message;
import foam.box.RPCMessage;
import foam.lib.json.Outputter;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.io.OutputStreamWriter;
import java.io.InputStream;
import java.io.IOException;

import org.apache.commons.io.IOUtils;
import javax.servlet.http.HttpServletResponse;
import foam.lib.json.JSONParser;

import foam.box.RPCMessage;
import foam.box.HTTPReplyBox;
import foam.box.Message;
import foam.box.RPCReturnMessage;

import java.nio.channels.SocketChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.ByteBuffer;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;

// Make sure that this class sould only have one instance in single journal mode.
// In multiple journal mode. each JDAO will have it's own instance.
// can simple get put to MedusaMediator
// Make sure MM initial before initial of this class.
// TODO: refactor this class as DAO.
// TODO: refactor all clusterNode finding in the a DAO. provide better controller of MN.
public class MMJournal extends AbstractJournal {

  private String serviceName;

  private final Map<Long, ArrayList<ClusterNode>> groupToMN = new HashMap<Long, ArrayList<ClusterNode>>();
  private final List<ArrayList<ClusterNode>> groups  = new LinkedList<ArrayList<ClusterNode>>();
  private final List<ClusterNode> availableNodes = new LinkedList<ClusterNode>();
  // Default TIME_OUT is 5 second
  private final long TIME_OUT = 10000;

  // globalIndex should be unique in each filename.
  // One MMJournal instance can be shared by different DAO(Single Journal Mode).
  // Method: Replay will update this index.
  private AtomicLong globalIndex = new AtomicLong(1);

  //Only record two entry for now.
  //TODO: need to initial parents.
  MedusaEntry parent1;
  MedusaEntry parent2;
  int hashIndex = 1;
  Object hashRecordLock = new Object();

  //TODO: check if this method is really threadsafe.
  private void updateHash(MedusaEntry parent) {
    synchronized ( hashRecordLock ) {
      if ( hashIndex == 1 ) {
        parent1 = parent;
        hashIndex = 2;
      } else {
        parent2 = parent;
        hashIndex =1;
      }
    }
  }

  private MMJournal(String serviceName) {
    this.serviceName = serviceName;
  }

  Object initialLock = new Object();
  private void initial(X x) {
    synchronized ( initialLock ) {
      if ( isInitialized ) return;

      if ( x == null ) throw new RuntimeException("Context miss.");
      DAO clusterNodeDAO = (DAO) x.get("clusterNodeDAO");
      if ( clusterNodeDAO == null ) throw new RuntimeException("clusterNodeDAO miss");

      GroupBy groupToInstance = (GroupBy) clusterNodeDAO
        .where(EQ(ClusterNode.TYPE, ClusterNodeType.MN))
        .select(GROUP_BY(ClusterNode.GROUP, new ArraySink.Builder(getX()).build()));

      for ( Object key : groupToInstance.getGroups().keySet() ) {
        for ( Object value: ((ArraySink) groupToInstance.getGroups().get(key)).getArray() ) {
          ClusterNode clusterNode = (ClusterNode) value;
          if ( groupToMN.get(clusterNode.getGroup()) == null ) {
            groupToMN.put(clusterNode.getGroup(), new ArrayList<ClusterNode>());
          }
          System.out.println(clusterNode);
          groupToMN.get(clusterNode.getGroup()).add(clusterNode);
          availableNodes.add(clusterNode);
        }
      }

      for ( Long group : groupToMN.keySet() ) {
        groups.add(groupToMN.get(group));
      }

      //TODO: remove below code. it only use for test.
      parent1 = new MedusaEntry();
      parent1.setMyIndex(1);
      parent1.setMyHash("aa");
      parent2 = new MedusaEntry();
      parent2.setMyIndex(2);
      parent2.setMyHash("bb");

      isInitialized = true;

    }
  }


  private ArrayList removeMN(long group) {
    return groupToMN.remove(group);
  }

  // GlobalIndex should only set in replay.
  public void setGlobalIndex(Long index) {
    globalIndex.set(index);
  }

  //TODO: provide better to do this.
  Object lock = new Object();
  int robin = 0 ;
  public int nextRobin() {
    synchronized ( lock ) {
      if ( robin == 1000000 ) return ( robin = 0 );
      else return robin++;
    }
  }

  // The method is thread-safe.
  public Long getGlobalIndex() {
    return globalIndex.getAndIncrement();
  }


  private static Map<String, MMJournal> journalMap = new HashMap<String, MMJournal>();

  public synchronized static MMJournal getMMjournal(String serviceName) {
    if ( journalMap.get(serviceName) != null ) return journalMap.get(serviceName);
    journalMap.put(serviceName, new MMJournal(serviceName));
    return journalMap.get(serviceName);
  }

  public volatile boolean isVersion = true;
  private volatile boolean isInitialized = false;
  // We will remove synchronized key word in the DAO put.
  // This method has to be thread safe.
  // The method only work if FObject implement Identifiable.Identifiable
  // Version is used to allow we can make parallel call.
  // TODO: can we do this versioning code at the begnning of DAO?
  @Override
  public FObject put(X x, String prefix, DAO dao, FObject obj) {
    if ( ! isInitialized ) initial(x);
    if ( isReplayed == false ) throw new RuntimeException("Can not do put without replay.");
    long myIndex = getGlobalIndex();

    // Get whole entry first to make sure threadsafe.
    MedusaEntry p1 = parent1;
    MedusaEntry p2 = parent2;
    Message msg =
      createMessage(
        p1.getMyIndex(),
        p1.getMyHash(),
        p2.getMyIndex(),
        p2.getMyHash(),
        myIndex,
        "put_",
        "p",
        null,
        prefix,
        obj
      );
    Outputter outputter = new Outputter(getX());

    String mn = outputter.stringify(msg);
    callMN(mn);
    return obj;
  }

  @Override
  public FObject remove(X x, String prefix, DAO dao, FObject obj) {
    if ( ! isInitialized ) initial(x);
    if ( isReplayed == false ) throw new RuntimeException("Can not do put without replay.");

    long myIndex = getGlobalIndex();
    // Get whole entry first to make sure threadsafe.
    MedusaEntry p1 = parent1;
    MedusaEntry p2 = parent2;
    Message msg =
      createMessage(
        p1.getMyIndex(),
        p1.getMyHash(),
        p2.getMyIndex(),
        p2.getMyHash(),
        myIndex,
        "remove_",
        "r",
        null,
        prefix,
        obj
      );
    Outputter outputter = new Outputter(getX());

    String mn = outputter.stringify(msg);
    callMN(mn);
    return obj;
  }


  private void callMN(String medusaEntry) {

    int index = nextRobin() % groups.size();
    int i = 0;
    int totalTry = groups.size();
    boolean isPersist = false;
    System.out.println("totalTry");
    System.out.println(totalTry);

    while ( i < totalTry ) {
      System.out.println("try");
      ArrayList<ClusterNode> nodes = groups.get(index / totalTry);
      Object[] tasks = new Object[nodes.size()];
      System.out.println(nodes.size());

      for ( int j = 0 ; j < nodes.size() ; j++ ) {
        ClusterNode node = nodes.get(j);
        tasks[j] = new FutureTask<String>(new Sender(node.getIp(), node.getServicePort(), medusaEntry));
        //TODO: use threadpool.
        new Thread((FutureTask<String>) tasks[j]).start();
      }

      long endtime = System.currentTimeMillis() + TIME_OUT * (i + 1);
      int check = 0;
      boolean[] checks = new boolean[nodes.size()];
      Arrays.fill(checks, false);

      MedusaEntry p = null;
      int threhold = 1;

      while ( System.currentTimeMillis() < endtime && Math.abs(check) < threhold ) {
        for ( int j = 0 ; j < tasks.length ; j++ ) {
          if ( checks[j] == false && ((FutureTask<String>) tasks[j]).isDone() ) {
            FutureTask<String> task = (FutureTask<String>) tasks[j];
            try {
              String response = task.get();
              //TODO: a bug, return message format wrong.
              Message responseMessage = (Message) getX().create(JSONParser.class).parseString(response);
              System.out.println("response>>>>>>>>");
              System.out.println(response);
              p = (MedusaEntry) ((RPCReturnMessage) responseMessage.getObject()).getData();
              if ( p instanceof MedusaEntry ) {
                check++;
              } else {
                check--;
              }
            } catch ( Exception e ) {
              //TODO: log error
              System.out.println(e);
              check--;
            } finally {
              checks[j] = true;
            }
          }
        }
      }

      if ( check >= threhold ) {
        isPersist = true;
        updateHash(p);
        break;
      }

      index++;
      i++;
    }



    // Important
    if ( isPersist == false ) {
      //TODO: shutdown the put method.
      throw new RuntimeException("MN do not work....");
    }

  }

  private Message createMessage(
    long globalIndex1,
    String hash1,
    long globalIndex2,
    String hash2,
    long myIndex,
    String method,
    String action,
    FObject old,
    String prefix,
    FObject nu
  ) {
    Message message = getX().create(Message.class);
    RPCMessage rpc = getX().create(foam.box.RPCMessage.class);
    //put or remove
    rpc.setName(method);
    MedusaEntry entry = getX().create(MedusaEntry.class);
    entry.setServiceName(serviceName);
    // p or r
    entry.setAction(action);
    entry.setGlobalIndex1(globalIndex1);
    entry.setHash1(hash1);
    entry.setGlobalIndex2(globalIndex2);
    entry.setHash2(hash2);
    entry.setMyIndex(myIndex);
    entry.setOld(old);
    entry.setNspecKey(prefix);
    entry.setNu(nu);
    Object[] args = {null, entry};
    rpc.setArgs(args);

    message.setObject(rpc);
    HTTPReplyBox replyBox = getX().create(HTTPReplyBox.class);
    message.getAttributes().put("replyBox", replyBox);
    return message;
  }

  private TcpMessage createListenMessage() {
    TcpMessage tcpMessage = new TcpMessage();
    tcpMessage.setServiceKey(serviceName);
    TcpSocketChannelSinkBox replyBox = new TcpSocketChannelSinkBox();
    tcpMessage.getAttributes().put("replyBox", replyBox);
    tcpMessage.getAttributes().put("sessionId", "aaaa");
    RPCMessage rpc = new RPCMessage();
    rpc.setName("listen_");
    Object[] args = {null, new TcpSocketChannelSink(), null};
    rpc.setArgs(args);
    tcpMessage.setObject(rpc);
    return tcpMessage;
  }


  private class Sender implements Callable<String> {
    private String ip;
    private String message;
    private int port;

    public Sender(String ip, int port, String message) {
      this.ip = ip;
      this.message = message;
      this.port = port;
    }

    public String call() throws Exception {
      HttpURLConnection conn = null;
      OutputStreamWriter output = null;
      InputStream input = null;

      try {
        System.out.println("aaaaccccc");
        URL url = new URL("Http", ip, port, "/service/" + serviceName);
        System.out.println(url);

        conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/json");

        output =
          new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8);

        output.write(message);
        output.close();

         // check response code
        int code = conn.getResponseCode();
        if ( code != HttpServletResponse.SC_OK ) {
          throw new RuntimeException("Http server return: " + code);
        }

        byte[] buf = new byte[8388608];
        input = conn.getInputStream();

        int off = 0;
        int len = buf.length;
        int read = -1;
        while ( len != 0 && ( read = input.read(buf, off, len) ) != -1 ) {
          off += read;
          len -= read;
        }

        if ( len == 0 && read != -1 ) {
          throw new RuntimeException("Message too large.");
        }

        return  new String(buf, 0, off, StandardCharsets.UTF_8);
      } catch ( Exception e ) {
        System.out.println(e);
        throw e;
      } finally {
        IOUtils.closeQuietly(output);
        IOUtils.closeQuietly(input);
        if ( conn != null ) {
          conn.disconnect();
        }
      }
    }
  }

  // Record SocketChannel for each node.
  private Map<Long, SocketChannel> nodeToSocketChannel;
  private Map<String, List<FObject>> readyToUseEntry;
  private Map<String, DAO> registerDAOs;

  private final void initialReplay(X x) {
    //TODO: close all socketchannel.
    if ( nodeToSocketChannel != null ) {
      for ( Map.Entry<Long, SocketChannel> entry: nodeToSocketChannel.entrySet() ) {
        TCPNioServer.closeSocketChannel(entry.getValue());
      }

    }
    nodeToSocketChannel = new HashMap<Long, SocketChannel>();
    readyToUseEntry = new HashMap<String, List<FObject>>();
    registerDAOs = new HashMap<String, DAO>();
  }

  // Make sure only replay once.
  private volatile boolean isReplayed = false;
  //TODO: add npsec into function.
  public synchronized void replay(X x, DAO dao) {
    //TODO: need a speciall dao.
    if ( ! isInitialized ) initial(x);

    // Only replay once.
    if ( ! isReplayed ) {
      initialReplay(x);
      try {
        List<MedusaEntry> entries = retrieveData(x, groupToMN);
        cacheOrMDAO(entries);
      } catch ( Exception e ) {
        //TODO: retry or stop system.
        throw new RuntimeException(e);
      }
      isReplayed = false;
    }

    if ( dao == null ) return;

    // Disable put when doing replay to a dao.
    synchronized ( cacheOrMDAOLock ) {
      String nspecKey = null;
      if ( readyToUseEntry.get(nspecKey) == null ) {
        System.out.println("No cached entry associated with: " + nspecKey);
        return;
      }
      for ( FObject obj : readyToUseEntry.get(nspecKey) ) {
        dao.put(obj);
      }
      readyToUseEntry.remove(nspecKey);
      registerDAOs.put(nspecKey, dao);
    }
  }


  private Object cacheOrMDAOLock = new Object();
  //Only one thread can access this function at any give time. When instance is secondary.
  private void cacheOrMDAO(MedusaEntry entry) {
    synchronized ( cacheOrMDAOLock ) {
      if ( registerDAOs.get(entry.getNspecKey()) != null ) {
        DAO dao = registerDAOs.get(entry.getNspecKey());
        dao.put(entry.getNu());
      } else {
        if ( readyToUseEntry.get(entry.getNspecKey()) == null ) {
          readyToUseEntry.put(entry.getNspecKey(), new LinkedList() );
        }
        List<FObject> entryList = readyToUseEntry.get(entry.getNspecKey());
        entryList.add(entry.getNu());
      }

      //TODO: update globalIndex and parent, varify hash.

    }
  }

  private void cacheOrMDAO(List<MedusaEntry> entries) {
    synchronized ( cacheOrMDAOLock ) {
      for ( MedusaEntry entry :  entries ) {
        cacheOrMDAO(entry);
      }
    }
  }


  private final List<MedusaEntry> retrieveData(X x, Map<Long, ArrayList<ClusterNode>> groupToMN) {

    // MedusaNode id to Bytebuffer.
    Map<Long, Map<Long, LinkedList<ByteBuffer>>> groupToJournal = new HashMap<Long, Map<Long, LinkedList<ByteBuffer>>>();
    Map<Long, List<MedusaEntry>> groupToEntry = new HashMap<Long, List<MedusaEntry>>();
    SocketChannel channel = null;
    try {
      for ( Map.Entry<Long, ArrayList<ClusterNode>> entry : groupToMN.entrySet() ) {
        long groupId = entry.getKey();
        Map<Long, LinkedList<ByteBuffer>> nodeToBuffers = new HashMap<Long, LinkedList<ByteBuffer>>();
        groupToJournal.put(groupId, nodeToBuffers);

        for ( ClusterNode node : entry.getValue() ) {
          channel = SocketChannel.open();
          channel.configureBlocking(true);
          InetSocketAddress address = new InetSocketAddress(node.getIp(), node.getSocketPort());
          //The system should wait for connection at here.
          boolean connectResult = channel.connect(address);

          if ( connectResult == false )
            throw new RuntimeException("Replay can not connect to: " + node.getId());

          nodeToSocketChannel.put(node.getId(), channel);

          nodeToBuffers.put(node.getId(), retrieveDataFromNode(x, channel));
          //TODO: record last entry from ench node.
          //TODO: get entry from readBuffer and but into dao.
          //TODO: After replay If not primary add socket channel into a selector. and set blocking == false.
        }

        groupToEntry.put(groupId, concatEntries(parseEntries(x, nodeToBuffers)));
      }

      System.out.println(">>>>>replay journal");
      printAll(x, groupToJournal);
      System.out.println("------replay journal end");
      return sortEntries(mergeEntries(groupToEntry));

    } catch ( IOException ioe ) {
      TCPNioServer.closeSocketChannel(channel);
      throw new RuntimeException(ioe);
    }

  }

  private final LinkedList<ByteBuffer> retrieveDataFromNode(X x, SocketChannel channel) throws IOException {
      LinkedList<ByteBuffer> buffers = new LinkedList<ByteBuffer>();
      ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
      TcpMessage replayInitialMessage = new TcpMessage();
      replayInitialMessage.setServiceKey("MNService");
      RPCMessage rpc = x.create(RPCMessage.class);
      rpc.setName("replayAll");
      Object[] args = { null, serviceName };
      rpc.setArgs(args);
      replayInitialMessage.setObject(rpc);

      Outputter outputter = new Outputter(x);
      String msg = outputter.stringify(replayInitialMessage);
      byte[] bytes = msg.getBytes(Charset.forName("UTF-8"));
      ByteBuffer ackBuffer = ByteBuffer.allocate(4 + bytes.length);
      ackBuffer.putInt(bytes.length);
      ackBuffer.put(bytes);
      ackBuffer.flip();
      channel.write(ackBuffer);
      // Waiting for ACK from MN.
      lengthBuffer.clear();

      if ( channel.read(lengthBuffer) < 0 ) throw new RuntimeException("End of Stream");
      lengthBuffer.flip();

      int ackLength = lengthBuffer.getInt();
      if ( ackLength < 0 ) throw new RuntimeException("End of Stream");
      ByteBuffer packet = ByteBuffer.allocate(ackLength);
      if ( channel.read(packet) < 0 ) throw new RuntimeException("End of Stream");

      packet.flip();
      String ackString = new String(packet.array(), 0, ackLength, Charset.forName("UTF-8"));
      FilePacket filePacket = (FilePacket) x.create(JSONParser.class).parseString(ackString);
      int totalBlock = filePacket.getTotalBlock();

      for ( int i = 0 ; i < totalBlock ; i++ ) {
        lengthBuffer.clear();

        channel.read(lengthBuffer);
        lengthBuffer.flip();
        int length = lengthBuffer.getInt();
        ByteBuffer readBuffer = ByteBuffer.allocate(length);

        channel.read(readBuffer);
        readBuffer.flip();
        buffers.add(readBuffer);
      }

      return buffers;
  }

  private final Map<Long, List<MedusaEntry>> parseEntries(X x, Map<Long, LinkedList<ByteBuffer>> nodeTojournal) {
    Map<Long, List<MedusaEntry>> ret = new HashMap<Long, List<MedusaEntry>>();
    Map<Long, Integer> count = new HashMap<Long, Integer>();
    for ( Map.Entry<Long, LinkedList<ByteBuffer>> entry2: nodeTojournal.entrySet() ) {
      long clusterNodeId = entry2.getKey();
      List<MedusaEntry> medusaEntrys = new LinkedList<MedusaEntry>();
      ret.put(clusterNodeId, medusaEntrys);

      byte[] carryOverBytes = null;
      int carryOverLength = -1;
      byte[] carryOverLengthBytes = null;
      byte[] lengthBytes = null;

      for ( ByteBuffer buffer : entry2.getValue() ) {

        if ( carryOverLengthBytes != null ) {
          int remain = 4 - carryOverLengthBytes.length;
          byte[] unreadLengthBytes = new byte[remain];
          buffer.get(unreadLengthBytes);
          lengthBytes = new byte[4];
          System.arraycopy(carryOverLengthBytes, 0, lengthBytes, 0, carryOverLengthBytes.length);
          System.arraycopy(unreadLengthBytes, 0, lengthBytes, carryOverLengthBytes.length, unreadLengthBytes.length);
          int length = ByteBuffer.wrap(lengthBytes).getInt();
          carryOverLengthBytes = null;
        }

        if ( carryOverBytes != null ) {
          int remain = carryOverLength - carryOverBytes.length;
          byte[] remainBytes = new byte[remain];
          buffer.get(remainBytes);
          byte[] entryBytes = new byte[carryOverBytes.length + remainBytes.length];
          System.arraycopy(carryOverBytes, 0, entryBytes, 0, carryOverBytes.length);
          System.arraycopy(remainBytes, 0, entryBytes, carryOverBytes.length, remainBytes.length);
          String entry = new String(entryBytes, 0, carryOverLength, Charset.forName("UTF-8"));
          carryOverBytes = null;
          carryOverLength = -1;

          MedusaEntry medusaEntry = (MedusaEntry) x.create(JSONParser.class).parseString(entry);
          medusaEntrys.add(medusaEntry);
        }
        while ( buffer.hasRemaining() ) {
          int length;

          if ( lengthBytes != null ) {
            length = ByteBuffer.wrap(lengthBytes).getInt();
            lengthBytes = null;
          } else {
            length = buffer.getInt();
          }

          if ( buffer.remaining() < 4 ) {
            carryOverLengthBytes = new byte[buffer.remaining()];
            buffer.get(carryOverLengthBytes);
            carryOverBytes = null;
            carryOverLength = -1;
          } else if ( length > buffer.remaining() ) {
            carryOverBytes = new byte[buffer.remaining()];
            carryOverLength = length;
            buffer.get(carryOverBytes);
            carryOverLengthBytes = null;
          } else {
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            String entry = new String(bytes, 0, length, Charset.forName("UTF-8"));
            System.out.println(entry);

            MedusaEntry medusaEntry = (MedusaEntry) x.create(JSONParser.class).parseString(entry);
            medusaEntrys.add(medusaEntry);

            carryOverBytes = null;
            carryOverLength = -1;
            carryOverLengthBytes = null;
          }
        }
      }

      //TODO: record last entry for each node.
    }

    return ret;
  }

  // Verify entries from same group. And concat them into list.
  private final List<MedusaEntry> concatEntries(Map<Long, List<MedusaEntry>> nodeToEntry) {
    Map<MedusaEntry, Integer> entryCount = new HashMap<MedusaEntry, Integer>();

    for ( Map.Entry<Long, List<MedusaEntry>> entry2: nodeToEntry.entrySet() ) {
      for ( MedusaEntry entry : entry2.getValue() ) {
        Integer i = entryCount.get(entry);
        if ( i == null ) entryCount.put(entry, 1);
        else entryCount.put(entry, i.intValue() + 1);
      }
    }

    List<MedusaEntry> entryList = new LinkedList<MedusaEntry>();
    for ( Map.Entry<MedusaEntry, Integer> entry : entryCount.entrySet() ) {
      if ( entry.getValue().intValue() >= 2 ) entryList.add(entry.getKey());
    }

    return entryList;
  }

  private final List<MedusaEntry> sortEntries(List<MedusaEntry> entries) {
    Collections.sort(entries, new SortbyIndex());
    return entries;
  }

  private final List<MedusaEntry> mergeEntries(Map<Long, List<MedusaEntry>> groupToEntry) {

    ArrayList<List<MedusaEntry>> keyList = new ArrayList<List<MedusaEntry>>();
    List<MedusaEntry> ret = keyList.stream().flatMap(list -> list.stream()).collect(Collectors.toList());
    return ret;
  }

  private class SortbyIndex implements Comparator<MedusaEntry> {

    public int compare(MedusaEntry a, MedusaEntry b) {
      Long obj1 = a.getMyIndex();
      Long obj2 = b.getMyIndex();

      return obj1.compareTo(obj2);
    }
  }


  // Each group has its own processor
  private class Processor extends FoamThread {

    private final Selector selector;
    private volatile boolean isRunning;
    private final Long groupId;
    private final Queue<SocketChannel> acceptedSocketChannels;
    private X x;


    public Processor(X x, Long groupId) throws IOException {
      super(groupId.toString(), true);
      this.x = x;
      this.groupId = groupId;
      this.selector = Selector.open();
      isRunning = true;
      acceptedSocketChannels = new LinkedBlockingQueue<SocketChannel>();
    }

    public boolean acceptSocketChannel(SocketChannel channel) {
      if ( isRunning && acceptedSocketChannels.offer(channel) ) {
        wakeup();
        return true;
      }
      return false;
    }

    public void wakeup() {
      selector.wakeup();
    }

    public void close() {
      try {
        isRunning = false;
        selector.close();
      } catch ( IOException e ) {
        System.out.println(e);
      }
    }

    private void configureNewConnections() {
      SocketChannel socketChannel = acceptedSocketChannels.poll();

      while ( isRunning && socketChannel != null ) {
        SelectionKey key = null;
        try {
          socketChannel.configureBlocking(false);
          socketChannel.socket().setSoLinger(false, -1);
          socketChannel.socket().setTcpNoDelay(true);

          key = socketChannel.register(selector, SelectionKey.OP_READ);

        } catch ( IOException e ) {
          System.out.println(e);
          TCPNioServer.removeSelectionKey(key);
          TCPNioServer.hardCloseSocketChannel(socketChannel);
        }
        socketChannel = acceptedSocketChannels.poll();
      }
    }

    private void select() {
      try {
        selector.select();
        Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();

        while ( isRunning  && iterator.hasNext() ) {
          SelectionKey key = iterator.next();
          iterator.remove();

          if ( key.isValid() == false ) {
            TCPNioServer.removeSelectionKey(key);
            continue;
          }

          if ( key.isReadable() ) {
            processRequest(key);
          }
        }
      } catch ( IOException e ) {
        System.out.println(e);
      }
    }

    private void processRequest(SelectionKey key) {
      SocketChannel socketChannel = null;
      try {
        socketChannel = (SocketChannel) key.channel();
        ByteBuffer lenBuf = ByteBuffer.allocate(4);

        int rc = socketChannel.read(lenBuf);
        if ( rc < 0 ) throw new IOException("End of Stream");

        int messageLen = -1;
        if ( lenBuf.remaining() == 0 ) {
          lenBuf.flip();
          messageLen = lenBuf.getInt();
          lenBuf.clear();
        }
        if ( messageLen < 0 ) throw new IOException("Len error: " + messageLen);

        ByteBuffer msgBuf = ByteBuffer.allocate(messageLen);
        if ( socketChannel.read(msgBuf) < 0 ) throw new IOException("End of Stream");

        String msgStr = null;
        if ( msgBuf.remaining() == 0 ) {
          msgBuf.flip();
          msgStr = new String(msgBuf.array(), 0, messageLen, Charset.forName("UTF-8"));
          msgBuf.clear();
        }

        FObject msg = x.create(JSONParser.class).parseString(msgStr);

        if ( msg == null ) {
          System.out.println("Failed to parse request: " + msg);
          return;
        }

        if ( ! ( msg instanceof MedusaEntry ) ) {
          System.out.println("Request should be MedusaEntry");
        }

        MedusaEntry entry = (MedusaEntry) msg;

        processEntry(groupId, entry);
      } catch ( IOException e ) {
        System.out.println(e);
        TCPNioServer.removeSelectionKey(key);
        TCPNioServer.hardCloseSocketChannel(socketChannel);
      }
    }

    @Override
    public void run() {
      try {
        while ( isRunning ) {
          configureNewConnections();
          select();
        }

        for ( SelectionKey key : selector.keys() ) {
          TCPNioServer.removeSelectionKey(key);
        }

        SocketChannel socketChannel = acceptedSocketChannels.poll();
        while ( socketChannel != null ) {
          TCPNioServer.hardCloseSocketChannel(socketChannel);
          socketChannel = acceptedSocketChannels.poll();
        }
      } catch ( Exception e ) {
        System.out.println(e);
      }
    }
  }


  private void initialListener(X x) throws IOException {
    if ( processorsMap != null ) {
      for ( Map.Entry<Long, Processor> entry : processorsMap.entrySet() ) {
        entry.getValue().close();
      }
    }
    cachedEntry = new HashMap<Long, Map<MedusaEntry, Integer>>();
    for ( Long group : groupToMN.keySet() ) {
      cachedEntry.put(group, new HashMap<MedusaEntry, Integer>());
    }

    cachedEntryMap = new HashMap<Long, MedusaEntry>();

    processorsMap = new HashMap<Long, Processor>();
    //TODO: Initial processor.
    for ( Map.Entry<Long, ArrayList<ClusterNode>> group : groupToMN.entrySet() ) {
      Long groupId = group.getKey();
      Processor processor = new Processor(x, groupId);
      for ( ClusterNode node : group.getValue() ) {
        //TODO: create socketChannel.
        SocketChannel channel = null;
        //TODO: need to wait until connection success.
        processor.acceptSocketChannel(channel);
      }
    }
  }

  // The method is called when Secondary become primary.
  public void enableMNWrite(X x) {
    shutdwonProcessors();
    //TODO: verify MN.
    cachedEntryMap = null;
    cachedEntry = null;
  }

  // Map<groupId, Map<MedusaEntry, count>>
  private Map<Long, Map<MedusaEntry, Integer>> cachedEntry;
  // GroupId to Processor map.
  private Map<Long, Processor> processorsMap;
  // Map<myIndex, MedusaEntry>.
  private Map<Long, MedusaEntry> cachedEntryMap;
  private Object cachedEntryMapLock = new Object();

  private void processEntry(Long groupId, MedusaEntry entry) {
    Map<MedusaEntry, Integer> entryCount = cachedEntry.get(groupId);
    //TODO: provide a way to clear cache.
    synchronized ( entryCount ) {
      if ( entry.getMyIndex() < globalIndex.get() ) return;

      if ( entryCount.get(entry) == null ) {
        entryCount.put(entry, new Integer(1));
      } else if ( entryCount.get(entry) == 1 ) {
        addEntryIntoCachedMap(entry);
      } else {
        //ignore.
      }
    }
  }


  private void addEntryIntoCachedMap(MedusaEntry entry) {
    synchronized ( cachedEntryMapLock ) {
      cachedEntryMap.put(entry.getMyIndex(), entry);
    }
  }

  private void processEntryFromCachedMap(Long globalInex) {
    if ( cachedEntryMap.get(globalIndex) != null ) {
      synchronized ( cachedEntryMapLock ) {
        cacheOrMDAO(cachedEntryMap.get(globalIndex));
        cachedEntryMap.remove(globalIndex);
      }
    }
  }


  private void shutdwonProcessors() {
    if ( this.processorsMap != null ) {
      for ( Map.Entry<Long, Processor> entry : this.processorsMap.entrySet() ) {
        entry.getValue().close();
      }
    }
    this.processorsMap = null;
  }

  private class EntryLoader extends FoamThread {

    private volatile boolean isRunning;
    private X x;

    public EntryLoader(X x) {
      super("entryLoader");
      this.x = x;
    }

    public void close() {
      isRunning = false;
    }

    @Override
    public void run() {
      processEntryFromCachedMap(globalIndex.get() + 1L);
    }
  }

  // This method only use for test.
  // This method hold when the minimal ByteBuffer is greater than 4,
  // and a entry should not be accross more than two ByteBuffer.
  private final void printAll(X x, Map<Long, Map<Long, LinkedList<ByteBuffer>>> groupTojournal) {
    for ( Map.Entry<Long, Map<Long, LinkedList<ByteBuffer>>> entry1 : groupTojournal.entrySet() ) {
      long groupId = entry1.getKey();
      for ( Map.Entry<Long, LinkedList<ByteBuffer>> entry2: entry1.getValue().entrySet() ) {
        long clusterNodeId = entry2.getKey();
        byte[] carryOverBytes = null;
        int carryOverLength = -1;
        byte[] carryOverLengthBytes = null;
        byte[] lengthBytes = null;

        for ( ByteBuffer buffer : entry2.getValue() ) {

          if ( carryOverLengthBytes != null ) {
            int remain = 4 - carryOverLengthBytes.length;
            byte[] unreadLengthBytes = new byte[remain];
            buffer.get(unreadLengthBytes);
            lengthBytes = new byte[4];
            System.arraycopy(carryOverLengthBytes, 0, lengthBytes, 0, carryOverLengthBytes.length);
            System.arraycopy(unreadLengthBytes, 0, lengthBytes, carryOverLengthBytes.length, unreadLengthBytes.length);
            int length = ByteBuffer.wrap(lengthBytes).getInt();
          }

          if ( carryOverBytes != null ) {
            int remain = carryOverLength - carryOverBytes.length;
            byte[] remainBytes = new byte[remain];
            buffer.get(remainBytes);
            byte[] entryBytes = new byte[carryOverBytes.length + remainBytes.length];
            System.arraycopy(carryOverBytes, 0, entryBytes, 0, carryOverBytes.length);
            System.arraycopy(remainBytes, 0, entryBytes, carryOverBytes.length, remainBytes.length);
            String entry = new String(entryBytes, 0, carryOverLength, Charset.forName("UTF-8"));
            carryOverBytes = null;
            carryOverLength = -1;
            System.out.println(entry);
          }
          while ( buffer.hasRemaining() ) {
            int length;

            if ( lengthBytes != null ) {
              length = ByteBuffer.wrap(lengthBytes).getInt();
              lengthBytes = null;
            } else {
              length = buffer.getInt();
            }

            if ( buffer.remaining() < 4 ) {
              carryOverLengthBytes = new byte[buffer.remaining()];
              buffer.get(carryOverLengthBytes);
              carryOverBytes = null;
              carryOverLength = -1;
            } else if ( length > buffer.remaining() ) {
              carryOverBytes = new byte[buffer.remaining()];
              carryOverLength = length;
              buffer.get(carryOverBytes);
              carryOverLengthBytes = null;
            } else {
              byte[] bytes = new byte[length];
              buffer.get(bytes);
              String entry = new String(bytes, 0, length, Charset.forName("UTF-8"));
              System.out.println(entry);
              carryOverBytes = null;
              carryOverLength = -1;
              carryOverLengthBytes = null;
            }
          }
        }
      }
    }
  }

}
