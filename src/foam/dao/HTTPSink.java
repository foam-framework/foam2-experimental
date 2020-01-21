/**
 * @license
 * Copyright 2018 The FOAM Authors. All Rights Reserved.
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package foam.dao;

import foam.core.Detachable;
import foam.core.FObject;
import foam.lib.Outputter;
import foam.lib.json.OutputterMode;
import foam.lib.NetworkPropertyPredicate;
import foam.nanos.http.Format;
import foam.util.SafetyUtil;
import org.apache.commons.io.IOUtils;

import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class HTTPSink
    extends AbstractSink
{
  protected String url_;
  protected String bearerToken_;
  protected Format format_;

  public HTTPSink(String url, Format format) {
    this(url, "", format);
  }

  public HTTPSink(String url, String bearerToken, Format format) {
    url_ = url;
    bearerToken_ = bearerToken;
    format_ = format;
  }

  @Override
  public void put(Object obj, Detachable sub) {
    HttpURLConnection conn = null;

    try {
      Outputter outputter = null;
      conn = (HttpURLConnection) new URL(url_).openConnection();
      conn.setRequestMethod("POST");
      if ( ! SafetyUtil.isEmpty(bearerToken_) ) {
        conn.setRequestProperty("Authorization", "Bearer " + bearerToken_);
      }
      conn.setDoInput(true);
      conn.setDoOutput(true);
      if ( format_ == Format.JSON ) {
        outputter = new foam.lib.json.Outputter(getX()).setPropertyPredicate(new NetworkPropertyPredicate());
        conn.addRequestProperty("Accept", "application/json");
        conn.addRequestProperty("Content-Type", "application/json");
      } else if ( format_ == Format.XML ) {
        outputter = new foam.lib.xml.Outputter(OutputterMode.NETWORK);
        conn.addRequestProperty("Accept", "application/xml");
        conn.addRequestProperty("Content-Type", "application/xml");
      }
      conn.connect();

      try(OutputStream os = conn.getOutputStream()) {
        try(BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
          writer.write(outputter.stringify((FObject)obj));
          writer.flush();
        }
      }

      // check response code
      int code = conn.getResponseCode();
      if ( code != HttpServletResponse.SC_OK ) {
        throw new RuntimeException("Http server did not return 200.");
      }
    } catch (Throwable t) {
      throw new RuntimeException(t);
    } finally {
      if ( conn != null ) {
        conn.disconnect();
      }
    }
  }
}
