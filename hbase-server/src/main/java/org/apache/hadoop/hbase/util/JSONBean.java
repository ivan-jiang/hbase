/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.util;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.util.Iterator;
import java.util.Set;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeErrorException;
import javax.management.RuntimeMBeanException;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.TabularData;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hbase.thirdparty.com.google.gson.Gson;
import org.apache.hbase.thirdparty.com.google.gson.stream.JsonWriter;

/**
 * Utility for doing JSON and MBeans.
 */
public class JSONBean {
  private static final Log LOG = LogFactory.getLog(JSONBean.class);
  private static final Gson GSON = GsonUtil.createGson().create();

  /**
   * Use dumping out mbeans as JSON.
   */
  public interface Writer extends Closeable {

    void write(final String key, final String value) throws IOException;

    int write(final MBeanServer mBeanServer, final ObjectName qry, final String attribute,
      final boolean description) throws IOException;

    void flush() throws IOException;
  }

  /**
   * Notice that, closing the return {@link Writer} will not close the {@code writer} passed in, you
   * still need to close the {@code writer} by yourself.
   * <p/>
   * This is because that, we can only finish the json after you call {@link Writer#close()}. So if
   * we just close the {@code writer}, you can write nothing after finished the json.
   */
  public Writer open(final PrintWriter writer) throws IOException {
    final JsonWriter jsonWriter = GSON.newJsonWriter(new java.io.Writer() {

      @Override
      public void write(char[] cbuf, int off, int len) throws IOException {
        writer.write(cbuf, off, len);
      }

      @Override
      public void flush() throws IOException {
        writer.flush();
      }

      @Override
      public void close() throws IOException {
        // do nothing
      }
    });
    jsonWriter.setIndent("  ");
    jsonWriter.beginObject();
    return new Writer() {
      @Override
      public void flush() throws IOException {
        jsonWriter.flush();
      }

      @Override
      public void close() throws IOException {
        jsonWriter.endObject();
        jsonWriter.close();
      }

      @Override
      public void write(String key, String value) throws IOException {
        jsonWriter.name(key).value(value);
      }

      @Override
      public int write(MBeanServer mBeanServer, ObjectName qry, String attribute,
          boolean description) throws IOException {
        return JSONBean.write(jsonWriter, mBeanServer, qry, attribute, description);
      }
    };
  }

  /**
   * @param mBeanServer
   * @param qry
   * @param attribute
   * @param description
   * @return Return non-zero if failed to find bean. 0
   * @throws IOException
   */
  private static int write(final JsonWriter writer, final MBeanServer mBeanServer,
      final ObjectName qry, final String attribute, final boolean description) throws IOException {

    LOG.trace("Listing beans for " + qry);
    Set<ObjectName> names = null;
    names = mBeanServer.queryNames(qry, null);
    writer.name("beans").beginArray();
    Iterator<ObjectName> it = names.iterator();
    while (it.hasNext()) {
      ObjectName oname = it.next();
      MBeanInfo minfo;
      String code = "";
      String descriptionStr = null;
      Object attributeinfo = null;
      try {
        minfo = mBeanServer.getMBeanInfo(oname);
        code = minfo.getClassName();
        if (description) {
          descriptionStr = minfo.getDescription();
        }
        String prs = "";
        try {
          if ("org.apache.commons.modeler.BaseModelMBean".equals(code)) {
            prs = "modelerType";
            code = (String) mBeanServer.getAttribute(oname, prs);
          }
          if (attribute != null) {
            prs = attribute;
            attributeinfo = mBeanServer.getAttribute(oname, prs);
          }
        } catch (RuntimeMBeanException e) {
          // UnsupportedOperationExceptions happen in the normal course of business,
          // so no need to log them as errors all the time.
          if (e.getCause() instanceof UnsupportedOperationException) {
            if (LOG.isTraceEnabled()) {
              LOG.trace("Getting attribute " + prs + " of " + oname + " threw " + e);
            }
          } else {
            LOG.error("Getting attribute " + prs + " of " + oname + " threw an exception", e);
          }
          return 0;
        } catch (AttributeNotFoundException e) {
          // If the modelerType attribute was not found, the class name is used
          // instead.
          LOG.error("getting attribute " + prs + " of " + oname
              + " threw an exception", e);
        } catch (MBeanException e) {
          // The code inside the attribute getter threw an exception so log it,
          // and fall back on the class name
          LOG.error("getting attribute " + prs + " of " + oname
              + " threw an exception", e);
        } catch (RuntimeException e) {
          // For some reason even with an MBeanException available to them
          // Runtime exceptionscan still find their way through, so treat them
          // the same as MBeanException
          LOG.error("getting attribute " + prs + " of " + oname
              + " threw an exception", e);
        } catch (ReflectionException e) {
          // This happens when the code inside the JMX bean (setter?? from the
          // java docs) threw an exception, so log it and fall back on the
          // class name
          LOG.error("getting attribute " + prs + " of " + oname
              + " threw an exception", e);
        }
      } catch (InstanceNotFoundException e) {
        //Ignored for some reason the bean was not found so don't output it
        continue;
      } catch (IntrospectionException e) {
        // This is an internal error, something odd happened with reflection so
        // log it and don't output the bean.
        LOG.error("Problem while trying to process JMX query: " + qry
            + " with MBean " + oname, e);
        continue;
      } catch (ReflectionException e) {
        // This happens when the code inside the JMX bean threw an exception, so
        // log it and don't output the bean.
        LOG.error("Problem while trying to process JMX query: " + qry
            + " with MBean " + oname, e);
        continue;
      }

      writer.beginObject();
      writer.name("name").value(oname.toString());
      if (description && descriptionStr != null && descriptionStr.length() > 0) {
        writer.name("description").value(descriptionStr);
      }
      writer.name("modelerType").value(code);
      if (attribute != null && attributeinfo == null) {
        writer.name("result").value("ERROR");
        writer.name("message").value("No attribute with name " + attribute + " was found.");
        writer.endObject();
        writer.endArray();
        writer.close();
        return -1;
      }

      if (attribute != null) {
        writeAttribute(writer, attribute, descriptionStr, attributeinfo);
      } else {
        MBeanAttributeInfo[] attrs = minfo.getAttributes();
        for (int i = 0; i < attrs.length; i++) {
          writeAttribute(writer, mBeanServer, oname, description, attrs[i]);
        }
      }
      writer.endObject();
    }
    writer.endArray();
    return 0;
  }

  private static void writeAttribute(final JsonWriter writer, final MBeanServer mBeanServer,
      final ObjectName oname, final boolean description, final MBeanAttributeInfo attr)
      throws IOException {
    if (!attr.isReadable()) {
      return;
    }
    String attName = attr.getName();
    if ("modelerType".equals(attName)) {
      return;
    }
    if (attName.indexOf("=") >= 0 || attName.indexOf(":") >= 0 || attName.indexOf(" ") >= 0) {
      return;
    }
    String descriptionStr = description? attr.getDescription(): null;
    Object value = null;
    try {
      value = mBeanServer.getAttribute(oname, attName);
    } catch (RuntimeMBeanException e) {
      // UnsupportedOperationExceptions happen in the normal course of business,
      // so no need to log them as errors all the time.
      if (e.getCause() instanceof UnsupportedOperationException) {
        if (LOG.isTraceEnabled()) {
          LOG.trace("Getting attribute " + attName + " of " + oname + " threw " + e);
        }
      } else {
        LOG.error("getting attribute "+attName+" of "+oname+" threw an exception", e);
      }
      return;
    } catch (RuntimeErrorException e) {
      // RuntimeErrorException happens when an unexpected failure occurs in getAttribute
      // for example https://issues.apache.org/jira/browse/DAEMON-120
      LOG.debug("getting attribute "+attName+" of "+oname+" threw an exception", e);
      return;
    } catch (AttributeNotFoundException e) {
      //Ignored the attribute was not found, which should never happen because the bean
      //just told us that it has this attribute, but if this happens just don't output
      //the attribute.
      return;
    } catch (MBeanException e) {
      //The code inside the attribute getter threw an exception so log it, and
      // skip outputting the attribute
      LOG.error("getting attribute "+attName+" of "+oname+" threw an exception", e);
      return;
    } catch (RuntimeException e) {
      //For some reason even with an MBeanException available to them Runtime exceptions
      //can still find their way through, so treat them the same as MBeanException
      LOG.error("getting attribute "+attName+" of "+oname+" threw an exception", e);
      return;
    } catch (ReflectionException e) {
      //This happens when the code inside the JMX bean (setter?? from the java docs)
      //threw an exception, so log it and skip outputting the attribute
      LOG.error("getting attribute "+attName+" of "+oname+" threw an exception", e);
      return;
    } catch (InstanceNotFoundException e) {
      //Ignored the mbean itself was not found, which should never happen because we
      //just accessed it (perhaps something unregistered in-between) but if this
      //happens just don't output the attribute.
      return;
    }

    writeAttribute(writer, attName, descriptionStr, value);
  }

  private static void writeAttribute(JsonWriter writer, String attName, String descriptionStr,
    Object value) throws IOException {
    boolean description = false;
    if (descriptionStr != null && descriptionStr.length() > 0 && !attName.equals(descriptionStr)) {
      writer.name(attName);
      writer.beginObject();
      writer.name("description").value(descriptionStr);
      writer.name("value");
      writeObject(writer, value);
      writer.endObject();
    } else {
      writer.name(attName);
      writeObject(writer, value);
    }
  }

  private static void writeObject(final JsonWriter writer, final Object value) throws IOException {
    if (value == null) {
      writer.nullValue();
    } else {
      Class<?> c = value.getClass();
      if (c.isArray()) {
        writer.beginArray();
        int len = Array.getLength(value);
        for (int j = 0; j < len; j++) {
          Object item = Array.get(value, j);
          writeObject(writer, item);
        }
        writer.endArray();
      } else if(value instanceof Number) {
        Number n = (Number)value;
        double doubleValue = n.doubleValue();
        if (Double.isNaN(doubleValue) || Double.isInfinite(doubleValue)) {
          writer.value(n);
        } else {
          writer.value(n.toString());
        }
      } else if(value instanceof Boolean) {
        Boolean b = (Boolean)value;
        writer.value(b);
      } else if(value instanceof CompositeData) {
        CompositeData cds = (CompositeData)value;
        CompositeType comp = cds.getCompositeType();
        Set<String> keys = comp.keySet();
        writer.beginObject();
        for (String key : keys) {
          writeAttribute(writer, key, null, cds.get(key));
        }
        writer.endObject();
      } else if(value instanceof TabularData) {
        TabularData tds = (TabularData)value;
        writer.beginArray();
        for (Object entry : tds.values()) {
          writeObject(writer, entry);
        }
        writer.endArray();
      } else {
        writer.value(value.toString());
      }
    }
  }

  /**
   * Dump out a subset of regionserver mbeans only, not all of them, as json on System.out.
   * @throws MalformedObjectNameException
   * @throws IOException
   */
  public static String dumpRegionServerMetrics() throws MalformedObjectNameException, IOException {
    StringWriter sw = new StringWriter(1024 * 100); // Guess this size
    try (PrintWriter writer = new PrintWriter(sw)) {
      JSONBean dumper = new JSONBean();
      try (JSONBean.Writer jsonBeanWriter = dumper.open(writer)) {
        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
        jsonBeanWriter.write(mbeanServer,
          new ObjectName("java.lang:type=Memory"), null, false);
        jsonBeanWriter.write(mbeanServer,
          new ObjectName("Hadoop:service=HBase,name=RegionServer,sub=IPC"), null, false);
        jsonBeanWriter.write(mbeanServer,
          new ObjectName("Hadoop:service=HBase,name=RegionServer,sub=Replication"), null, false);
        jsonBeanWriter.write(mbeanServer,
          new ObjectName("Hadoop:service=HBase,name=RegionServer,sub=Server"), null, false);
      }
    }
    sw.close();
    return sw.toString();
  }

  /**
   * Dump out all registered mbeans as json on System.out.
   * @throws IOException
   * @throws MalformedObjectNameException
   */
  public static void dumpAllBeans() throws IOException, MalformedObjectNameException {
    try (PrintWriter writer = new PrintWriter(System.out)) {
      JSONBean dumper = new JSONBean();
      try (JSONBean.Writer jsonBeanWriter = dumper.open(writer)) {
        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
        jsonBeanWriter.write(mbeanServer, new ObjectName("*:*"), null, false);
      }
    }
  }

  public static void main(String[] args) throws IOException, MalformedObjectNameException {
    String str = dumpRegionServerMetrics();
    System.out.println(str);
  }
}
