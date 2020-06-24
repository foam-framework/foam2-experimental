/**
 * @license
 * Copyright 2020 The FOAM Authors. All Rights Reserved.
 * http://www.apache.org/licenses/LICENSE-2.0
 */

foam.CLASS({
  package: 'foam.nanos.medusa',
  name: 'MedusaBroadcastDAO',
  extends: 'foam.nanos.medusa.BatchClientDAO',

  documentation: `Broadcast MedusaEntries.`,

  javaImports: [
    'foam.core.Agency',
    'foam.core.ContextAgent',
    'foam.core.FObject',
    'foam.core.X',
    'foam.dao.ArraySink',
    'foam.dao.DAO',
    'foam.dao.DOP',
    'static foam.mlang.MLang.AND',
    'static foam.mlang.MLang.EQ',
    'static foam.mlang.MLang.OR',
    'foam.mlang.predicate.Predicate',
    'foam.nanos.logger.PrefixLogger',
    'foam.nanos.logger.Logger',
    'foam.nanos.pm.PM',
    'java.util.ArrayList',
    'java.util.HashMap',
    'java.util.List',
    'java.util.Map'
  ],
  
  properties: [
    {
      name: 'serviceName',
      class: 'String',
      javaFactory: `
      return "medusaMediatorDAO";
      `
    },
    {
      name: 'predicate',
      class: 'foam.mlang.predicate.PredicateProperty',
      factory: function() { return foam.mlang.MLang.FALSE; },
      javaFactory: `
      ClusterConfigSupport support = (ClusterConfigSupport) getX().get("clusterConfigSupport");
      ClusterConfig myConfig = support.getConfig(getX(), support.getConfigId());
      Predicate zones = EQ(ClusterConfig.ZONE, myConfig.getZone() + 1);
      if ( myConfig.getZone() == 0L ) {
        zones =
          OR(
            EQ(ClusterConfig.ZONE, myConfig.getZone()),
            zones
          );
      }
      return
          AND(
            zones,
            EQ(ClusterConfig.TYPE, MedusaType.MEDIATOR),
            EQ(ClusterConfig.STATUS, Status.ONLINE),
            EQ(ClusterConfig.ENABLED, true),
            EQ(ClusterConfig.REGION, myConfig.getRegion()),
            EQ(ClusterConfig.REALM, myConfig.getRealm())
          );
      `
    },
    {
      // TODO: clear on ClusterConfig DAO updates
      name: 'clients',
      class: 'Map',
      javaFactory: 'return new HashMap();'
    },
    {
      class: 'FObjectProperty',
      of: 'foam.nanos.logger.Logger',
      name: 'logger',
      visibility: 'HIDDEN',
      javaFactory: `
        return new PrefixLogger(new Object[] {
          this.getClass().getSimpleName(),
          this.getServiceName()
        }, (Logger) getX().get("logger"));
      `
    },
  ],
  
  methods: [
    {
      name: 'init_',
      javaCode: `
      // TODO 
      // listen on ClusterConfigDAO updates.
      `
    },
    {
      name: 'put_',
      javaCode: `
      MedusaEntry entry = (MedusaEntry) obj;
      getLogger().debug("put", entry.getIndex());

      ClusterConfigSupport support = (ClusterConfigSupport) getX().get("clusterConfigSupport");
      ClusterConfig myConfig = support.getConfig(x, support.getConfigId());

      MedusaEntry old = (MedusaEntry) getDelegate().find_(x, entry.getId());
      entry = (MedusaEntry) getDelegate().put_(x, entry);

      if ( support.getStandAlone() ) {
        return ((DAO) x.get(getServiceName())).put(entry);
      }

      // Purely for human monitoring and troubleshooting.
      entry.setNode(System.getProperty("hostname"));

      if ( myConfig.getType() == MedusaType.NODE ) {
        // Always broadcast to/from NODE and
        // queue for broadcast
//        return super.put_(x, entry);
        submit(x, entry, DOP.PUT);
      } else if ( myConfig.getType() == MedusaType.MEDIATOR &&
        // Broadcast promoted entries to other MEDIATORS
        // REVIEW: to avoid broadcast during reply, wait until ONLINE,
        // mediators may miss data between replayComplete and status change to ONLINE.
                  myConfig.getStatus() == Status.ONLINE &&
                  ( entry.getPromoted() &&
                    ( old == null ||
                      ! old.getPromoted() ) ) ) {
        // queue for broadcast
//        return super.put_(x, entry);
        submit(x, entry, DOP.PUT);
      }
      return entry;
      `
    },
    {
      documentation: 'Using assembly line, write to all online mediators in zone 0 and same realm,region',
      name: 'cmd_',
      javaCode: `
      return submit(x, obj, DOP.CMD);
      `
    },
    {
      documentation: 'Using assembly line, write to mediators',
      name: 'submit',
      args: [
        {
          name: 'x',
          type: 'Context'
        },
        {
          name: 'obj',
          type: 'Object'
        },
        {
          name: 'dop',
          type: 'foam.dao.DOP'
        },
      ],
      type: 'Object',
      javaCode: `
      PM pm = createPM(x, dop.getLabel());
      try {
      getLogger().debug("submit", dop.getLabel(), obj.getClass().getSimpleName());

      ClusterConfigSupport support = (ClusterConfigSupport) x.get("clusterConfigSupport");
      ClusterConfig myConfig = support.getConfig(x, support.getConfigId());
      List<ClusterConfig> arr = (ArrayList) ((ArraySink) ((DAO) x.get("localClusterConfigDAO"))
        .where(getPredicate())
        .select(new ArraySink())).getArray();
      Agency agency = (Agency) x.get("threadPool");
      for ( ClusterConfig config : arr ) {
        getLogger().debug("submit", "job", config.getId(), dop.getLabel(), "assembly");
        agency.submit(x, new ContextAgent() {
          public void execute(X x) {
            getLogger().debug("agency", "execute", config.getId());
             try {
              DAO dao = (DAO) getClients().get(config.getId());
              if ( dao == null ) {
                dao = (DAO) x.get(getServiceName());
                if ( dao != null ) {
                  getLogger().debug("agency", "execute", "short circuit", getServiceName());
                } else {
                  dao = support.getClientDAO(x, getServiceName(), myConfig, config);
                  dao = new RetryClientSinkDAO.Builder(x)
                          .setDelegate(dao)
                          .setMaxRetryAttempts(support.getMaxRetryAttempts())
                          .setMaxRetryDelay(support.getMaxRetryDelay())
                          .build();
                }
              }
              getClients().put(config.getId(), dao);

              if ( DOP.PUT == dop ) {
                MedusaEntry entry = (MedusaEntry) obj;
                getLogger().debug("agency", "execute", config.getId(), dop.getLabel(), entry.getIndex());
                dao.put_(x, entry);
              } else if ( DOP.CMD == dop ) {
                getLogger().debug("agency", "execute", config.getId(), dop.getLabel(), obj.getClass().getSimpleName());
                dao.cmd_(x, obj);
              }
            } catch ( Throwable t ) {
              getLogger().error("agency", "execute", config.getId(), t);
            }
          }
        }, this.getClass().getSimpleName());
      }
      return obj;
      } catch (Throwable t) {
        getLogger().error(t.getMessage(), t);
        throw t;
      } finally {
        pm.log(x);
      }
      `
    },
    {
      name: 'createPM',
      args: [
        {
          name: 'x',
          type: 'Context'
        },
        {
          name: 'name',
          type: 'String'
        }
      ],
      javaType: 'PM',
      javaCode: `
      return PM.create(x, this.getOwnClassInfo(), getServiceName()+":"+name);
      `
    },
    {
      name: 'getBatchTimerInterval',
      args: [
        {
          name: 'x',
          type: 'Context'
        },
      ],
      type: 'Long',
      javaCode: `
      ClusterConfigSupport support = (ClusterConfigSupport) x.get("clusterConfigSupport");
      return support.getBatchTimerInterval();
      `
    },
    {
      name: 'getMaxBatchSize',
      args: [
        {
          name: 'x',
          type: 'Context'
        },
      ],
      type: 'Long',
      javaCode: `
      ClusterConfigSupport support = (ClusterConfigSupport) x.get("clusterConfigSupport");
      return support.getMaxBatchSize();
      `
    }
   ]
});
