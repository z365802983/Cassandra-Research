/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.config;

import org.apache.cassandra.auth.AllowAllAuthenticator;
import org.apache.cassandra.auth.IAuthenticator;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.commitlog.CommitLog;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.locator.EndPointSnitch;
import org.apache.cassandra.locator.IEndPointSnitch;
import org.apache.cassandra.locator.AbstractReplicationStrategy;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.utils.XMLUtils;
import org.apache.log4j.Logger;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.xpath.XPathExpressionException;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URL;

public class DatabaseDescriptor
{
    private static Logger logger_ = Logger.getLogger(DatabaseDescriptor.class);

    // don't capitalize these; we need them to match what's in the config file for CLS.valueOf to parse
    public static enum CommitLogSync {
        periodic,
        batch
    }

    public static enum DiskAccessMode {
        auto,
        mmap,
        mmap_index_only,
        standard,
    }

    public static final String random_ = "RANDOM";
    public static final String ophf_ = "OPHF";
    private static int storagePort_ = 7000;
    private static int controlPort_ = 7001;
    private static int rpcPort_ = 9160;
    private static boolean thriftFramed_ = false;
    private static InetAddress listenAddress_; // leave null so we can fall through to getLocalHost
    private static InetAddress rpcAddress_;
    private static String clusterName_ = "Test";
    private static long rpcTimeoutInMillis_ = 2000;
    private static Set<InetAddress> seeds_ = new HashSet<InetAddress>();
    /* Keeps the list of data file directories */
    private static String[] dataFileDirectories_;
    /* Current index into the above list of directories */
    private static int currentIndex_ = 0;
    private static String logFileDirectory_;
    private static int consistencyThreads_ = 4; // not configurable
    private static int concurrentReaders_ = 8;
    private static int concurrentWriters_ = 32;

    private static double flushDataBufferSizeInMB_ = 32;
    private static double flushIndexBufferSizeInMB_ = 8;
    private static int slicedReadBufferSizeInKB_ = 64;

    static Map<String, KSMetaData> tables_ = new HashMap<String, KSMetaData>();
    private static int bmtThreshold_ = 256;

    /* Hashing strategy Random or OPHF */
    private static IPartitioner partitioner_;

    /* if the size of columns or super-columns are more than this, indexing will kick in */
    private static int columnIndexSizeInKB_;
    /* Number of minutes to keep a memtable in memory */
    private static int memtableLifetimeMs_ = 60 * 60 * 1000;
    /* Size of the memtable in memory before it is dumped */
    private static int memtableThroughput_ = 64;
    /* Number of objects in millions in the memtable before it is dumped */
    private static double memtableOperations_ = 0.1;
    /* 
     * This parameter enables or disables consistency checks. 
     * If set to false the read repairs are disable for very
     * high throughput on reads but at the cost of consistency.
    */
    private static boolean doConsistencyCheck_ = true;
    /* Job Jar Location */
    private static String jobJarFileLocation_;
    /* Address where to run the job tracker */
    private static String jobTrackerHost_;    
    /* time to wait before garbage collecting tombstones (deletion markers) */
    private static int gcGraceInSeconds_ = 10 * 24 * 3600; // 10 days

    // the path qualified config file (storage-conf.xml) name
    private static String configFileName_;
    /* initial token in the ring */
    private static String initialToken_ = null;

    private static CommitLogSync commitLogSync_;
    private static double commitLogSyncBatchMS_;
    private static int commitLogSyncPeriodMS_;

    private static DiskAccessMode diskAccessMode_;
    private static DiskAccessMode indexAccessMode_;

    private static boolean snapshotBeforeCompaction_;
    private static boolean autoBootstrap_ = false;

    private static IAuthenticator authenticator = new AllowAllAuthenticator();

    private final static String STORAGE_CONF_FILE = "storage-conf.xml";

    /**
     * Try the storage-config system property, and then inspect the classpath.
     */
    static String getStorageConfigPath()
    {
        String scp = System.getProperty("storage-config") + File.separator + STORAGE_CONF_FILE;
        if (new File(scp).exists())
            return scp;
        // try the classpath
        ClassLoader loader = DatabaseDescriptor.class.getClassLoader();
        URL scpurl = loader.getResource(STORAGE_CONF_FILE);
        if (scpurl != null)
            return scpurl.getFile();
        throw new RuntimeException("Cannot locate " + STORAGE_CONF_FILE + " via storage-config system property or classpath lookup.");
    }

    static
    {
        try
        {
            configFileName_ = getStorageConfigPath();
            if (logger_.isDebugEnabled())
                logger_.debug("Loading settings from " + configFileName_);
            XMLUtils xmlUtils = new XMLUtils(configFileName_);

            /* Cluster Name */
            clusterName_ = xmlUtils.getNodeValue("/Storage/ClusterName");

            String syncRaw = xmlUtils.getNodeValue("/Storage/CommitLogSync");
            try
            {
                commitLogSync_ = CommitLogSync.valueOf(syncRaw);
            }
            catch (IllegalArgumentException e)
            {
                throw new ConfigurationException("CommitLogSync must be either 'periodic' or 'batch'");
            }
            if (commitLogSync_ == null)
            {
                throw new ConfigurationException("Missing required directive CommitLogSync");
            }
            else if (commitLogSync_ == CommitLogSync.batch)
            {
                try
                {
                    commitLogSyncBatchMS_ = Double.valueOf(xmlUtils.getNodeValue("/Storage/CommitLogSyncBatchWindowInMS"));
                }
                catch (Exception e)
                {
                    throw new ConfigurationException("Unrecognized value for CommitLogSyncBatchWindowInMS.  Double expected.");
                }
                if (xmlUtils.getNodeValue("/Storage/CommitLogSyncPeriodInMS") != null)
                {
                    throw new ConfigurationException("Batch sync specified, but CommitLogSyncPeriodInMS found.  Only specify CommitLogSyncBatchWindowInMS when using batch sync.");
                }
                logger_.debug("Syncing log with a batch window of " + commitLogSyncBatchMS_);
            }
            else
            {
                assert commitLogSync_ == CommitLogSync.periodic;
                try
                {
                    commitLogSyncPeriodMS_ = Integer.valueOf(xmlUtils.getNodeValue("/Storage/CommitLogSyncPeriodInMS"));
                }
                catch (Exception e)
                {
                    throw new ConfigurationException("Unrecognized value for CommitLogSyncPeriodInMS.  Integer expected.");
                }
                if (xmlUtils.getNodeValue("/Storage/CommitLogSyncBatchWindowInMS") != null)
                {
                    throw new ConfigurationException("Periodic sync specified, but CommitLogSyncBatchWindowInMS found.  Only specify CommitLogSyncPeriodInMS when using periodic sync.");
                }
                logger_.debug("Syncing log with a period of " + commitLogSyncPeriodMS_);
            }

            String modeRaw = xmlUtils.getNodeValue("/Storage/DiskAccessMode");
            try
            {
                diskAccessMode_ = DiskAccessMode.valueOf(modeRaw);
            }
            catch (IllegalArgumentException e)
            {
                throw new ConfigurationException("DiskAccessMode must be either 'auto', 'mmap', 'mmap_index_only', or 'standard'");
            }
            if (diskAccessMode_ == DiskAccessMode.auto)
            {
                diskAccessMode_ = System.getProperty("os.arch").contains("64") ? DiskAccessMode.mmap : DiskAccessMode.standard;
                indexAccessMode_ = diskAccessMode_;
                logger_.info("Auto DiskAccessMode determined to be " + diskAccessMode_);
            }
            else if (diskAccessMode_ == DiskAccessMode.mmap_index_only)
            {
                diskAccessMode_ = DiskAccessMode.standard;
                indexAccessMode_ = DiskAccessMode.mmap;
            }
            else
            {
                indexAccessMode_ = diskAccessMode_;
            }

            /* Authentication and authorization backend, implementing IAuthenticator */
            String authenticatorClassName = xmlUtils.getNodeValue("/Storage/Authenticator");
            if (authenticatorClassName != null)
            {
                try
                {
                    Class cls = Class.forName(authenticatorClassName);
                    authenticator = (IAuthenticator) cls.getConstructor().newInstance();
                }
                catch (ClassNotFoundException e)
                {
                    throw new ConfigurationException("Invalid authenticator class " + authenticatorClassName);
                }
            }
            
            /* Hashing strategy */
            String partitionerClassName = xmlUtils.getNodeValue("/Storage/Partitioner");
            if (partitionerClassName == null)
            {
                throw new ConfigurationException("Missing partitioner directive /Storage/Partitioner");
            }
            try
            {
                Class cls = Class.forName(partitionerClassName);
                partitioner_ = (IPartitioner) cls.getConstructor().newInstance();
            }
            catch (ClassNotFoundException e)
            {
                throw new ConfigurationException("Invalid partitioner class " + partitionerClassName);
            }

            /* JobTracker address */
            jobTrackerHost_ = xmlUtils.getNodeValue("/Storage/JobTrackerHost");

            /* Job Jar file location */
            jobJarFileLocation_ = xmlUtils.getNodeValue("/Storage/JobJarFileLocation");

            String gcGrace = xmlUtils.getNodeValue("/Storage/GCGraceSeconds");
            if ( gcGrace != null )
                gcGraceInSeconds_ = Integer.parseInt(gcGrace);

            initialToken_ = xmlUtils.getNodeValue("/Storage/InitialToken");

            /* RPC Timeout */
            String rpcTimeoutInMillis = xmlUtils.getNodeValue("/Storage/RpcTimeoutInMillis");
            if ( rpcTimeoutInMillis != null )
                rpcTimeoutInMillis_ = Integer.parseInt(rpcTimeoutInMillis);

            /* Thread per pool */
            String rawReaders = xmlUtils.getNodeValue("/Storage/ConcurrentReads");
            if (rawReaders != null)
            {
                concurrentReaders_ = Integer.parseInt(rawReaders);
            }
            String rawWriters = xmlUtils.getNodeValue("/Storage/ConcurrentWrites");
            if (rawWriters != null)
            {
                concurrentWriters_ = Integer.parseInt(rawWriters);
            }

            String rawFlushData = xmlUtils.getNodeValue("/Storage/FlushDataBufferSizeInMB");
            if (rawFlushData != null)
            {
                flushDataBufferSizeInMB_ = Double.parseDouble(rawFlushData);
            }
            String rawFlushIndex = xmlUtils.getNodeValue("/Storage/FlushIndexBufferSizeInMB");
            if (rawFlushIndex != null)
            {
                flushIndexBufferSizeInMB_ = Double.parseDouble(rawFlushIndex);
            }

            String rawSlicedBuffer = xmlUtils.getNodeValue("/Storage/SlicedBufferSizeInKB");
            if (rawSlicedBuffer != null)
            {
                slicedReadBufferSizeInKB_ = Integer.parseInt(rawSlicedBuffer);
            }

            String bmtThreshold = xmlUtils.getNodeValue("/Storage/BinaryMemtableThroughputInMB");
            if (bmtThreshold != null)
            {
                bmtThreshold_ = Integer.parseInt(bmtThreshold);
            }

            /* TCP port on which the storage system listens */
            String port = xmlUtils.getNodeValue("/Storage/StoragePort");
            if ( port != null )
                storagePort_ = Integer.parseInt(port);

            /* Local IP or hostname to bind services to */
            String listenAddress = xmlUtils.getNodeValue("/Storage/ListenAddress");
            if (listenAddress != null)
            {
                if (listenAddress.equals("0.0.0.0"))
                    throw new ConfigurationException("ListenAddress must be a single interface.  See http://wiki.apache.org/cassandra/FAQ#cant_listen_on_ip_any");
                try
                {
                    listenAddress_ = InetAddress.getByName(listenAddress);
                }
                catch (UnknownHostException e)
                {
                    throw new ConfigurationException("Unknown ListenAddress '" + listenAddress + "'");
                }
            }

            /* Local IP or hostname to bind RPC server to */
            String rpcAddress = xmlUtils.getNodeValue("/Storage/RPCAddress");
            if ( rpcAddress != null )
                rpcAddress_ = InetAddress.getByName(rpcAddress);
            
            /* UDP port for control messages */
            port = xmlUtils.getNodeValue("/Storage/ControlPort");
            if ( port != null )
                controlPort_ = Integer.parseInt(port);

            /* get the RPC port from conf file */
            port = xmlUtils.getNodeValue("/Storage/RPCPort");
            if (port != null)
                rpcPort_ = Integer.parseInt(port);

            /* Framed (Thrift) transport (default to "no") */
            String framedRaw = xmlUtils.getNodeValue("/Storage/ThriftFramedTransport");
            if (framedRaw != null)
            {
                if (framedRaw.equalsIgnoreCase("true") || framedRaw.equalsIgnoreCase("false"))
                {
                    thriftFramed_ = Boolean.valueOf(framedRaw);
                }
                else
                {
                    throw new ConfigurationException("Unrecognized value for ThriftFramedTransport.  Use 'true' or 'false'.");
                }
            }

            /* snapshot-before-compaction.  defaults to false */
            String sbc = xmlUtils.getNodeValue("/Storage/SnapshotBeforeCompaction");
            if (sbc != null)
            {
                if (sbc.equalsIgnoreCase("true") || sbc.equalsIgnoreCase("false"))
                {
                    if (logger_.isDebugEnabled())
                        logger_.debug("setting snapshotBeforeCompaction to " + sbc);
                    snapshotBeforeCompaction_ = Boolean.valueOf(sbc);
                }
                else
                {
                    throw new ConfigurationException("Unrecognized value for SnapshotBeforeCompaction.  Use 'true' or 'false'.");
                }
            }

            /* snapshot-before-compaction.  defaults to false */
            String autoBootstrap = xmlUtils.getNodeValue("/Storage/AutoBootstrap");
            if (autoBootstrap != null)
            {
                if (autoBootstrap.equalsIgnoreCase("true") || autoBootstrap.equalsIgnoreCase("false"))
                {
                    if (logger_.isDebugEnabled())
                        logger_.debug("setting autoBootstrap to " + autoBootstrap);
                    autoBootstrap_ = Boolean.valueOf(autoBootstrap);
                }
                else
                {
                    throw new ConfigurationException("Unrecognized value for AutoBootstrap.  Use 'true' or 'false'.");
                }
            }

            /* Number of days to keep the memtable around w/o flushing */
            String lifetime = xmlUtils.getNodeValue("/Storage/MemtableFlushAfterMinutes");
            if (lifetime != null)
                memtableLifetimeMs_ = Integer.parseInt(lifetime) * 60 * 1000;

            /* Size of the memtable in memory in MB before it is dumped */
            String memtableSize = xmlUtils.getNodeValue("/Storage/MemtableThroughputInMB");
            if ( memtableSize != null )
                memtableThroughput_ = Integer.parseInt(memtableSize);
            /* Number of objects in millions in the memtable before it is dumped */
            String memtableObjectCount = xmlUtils.getNodeValue("/Storage/MemtableOperationsInMillions");
            if ( memtableObjectCount != null )
                memtableOperations_ = Double.parseDouble(memtableObjectCount);
            if (memtableOperations_ <= 0)
            {
                throw new ConfigurationException("Memtable object count must be a positive double");
            }

            /* This parameter enables or disables consistency checks.
             * If set to false the read repairs are disable for very
             * high throughput on reads but at the cost of consistency.*/
            String doConsistencyCheck = xmlUtils.getNodeValue("/Storage/DoConsistencyChecksBoolean");
            if ( doConsistencyCheck != null )
                doConsistencyCheck_ = Boolean.parseBoolean(doConsistencyCheck);

            /* read the size at which we should do column indexes */
            String columnIndexSizeInKB = xmlUtils.getNodeValue("/Storage/ColumnIndexSizeInKB");
            if(columnIndexSizeInKB == null)
            {
                columnIndexSizeInKB_ = 64;
            }
            else
            {
                columnIndexSizeInKB_ = Integer.parseInt(columnIndexSizeInKB);
            }

            /* data file and commit log directories. they get created later, when they're needed. */
            dataFileDirectories_ = xmlUtils.getNodeValues("/Storage/DataFileDirectories/DataFileDirectory");
            logFileDirectory_ = xmlUtils.getNodeValue("/Storage/CommitLogDirectory");

            /* threshold after which commit log should be rotated. */
            String value = xmlUtils.getNodeValue("/Storage/CommitLogRotationThresholdInMB");
            if ( value != null)
                CommitLog.setSegmentSize(Integer.parseInt(value) * 1024 * 1024);

            readTablesFromXml();
            if (tables_.isEmpty())
                throw new ConfigurationException("No keyspaces configured");

            // Hardcoded system tables
            KSMetaData systemMeta = new KSMetaData(Table.SYSTEM_TABLE, null, -1, null);
            tables_.put(Table.SYSTEM_TABLE, systemMeta);
            systemMeta.cfMetaData.put(SystemTable.STATUS_CF, new CFMetaData(Table.SYSTEM_TABLE,
                                                                            SystemTable.STATUS_CF,
                                                                            "Standard",
                                                                            new UTF8Type(),
                                                                            null,
                                                                            "persistent metadata for the local node",
                                                                            0d,
                                                                            0.01d));

            systemMeta.cfMetaData.put(HintedHandOffManager.HINTS_CF, new CFMetaData(Table.SYSTEM_TABLE,
                                                                                    HintedHandOffManager.HINTS_CF,
                                                                                    "Super",
                                                                                    new UTF8Type(),
                                                                                    new BytesType(),
                                                                                    "hinted handoff data",
                                                                                    0d,
                                                                                    0.01d));

            /* Load the seeds for node contact points */
            String[] seeds = xmlUtils.getNodeValues("/Storage/Seeds/Seed");
            if (seeds.length <= 0)
            {
                throw new ConfigurationException("A minimum of one seed is required.");
            }
            for( int i = 0; i < seeds.length; ++i )
            {
                seeds_.add(InetAddress.getByName(seeds[i]));
            }
        }
        catch (ConfigurationException e)
        {
            logger_.error("Fatal error: " + e.getMessage());
            System.err.println("Bad configuration; unable to start server");
            System.exit(1);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private static void readTablesFromXml() throws ConfigurationException
    {
        XMLUtils xmlUtils = null;
        try
        {
            xmlUtils = new XMLUtils(configFileName_);
        }
        catch (ParserConfigurationException e)
        {
            ConfigurationException ex = new ConfigurationException(e.getMessage());
            ex.initCause(e);
            throw ex;
        }
        catch (SAXException e)
        {
            ConfigurationException ex = new ConfigurationException(e.getMessage());
            ex.initCause(e);
            throw ex;
        }
        catch (IOException e)
        {
            ConfigurationException ex = new ConfigurationException(e.getMessage());
            ex.initCause(e);
            throw ex;
        }

        /* Read the table related stuff from config */
        try
        {
            NodeList tables = xmlUtils.getRequestedNodeList("/Storage/Keyspaces/Keyspace");
            int size = tables.getLength();
            for ( int i = 0; i < size; ++i )
            {
                String value = null;
                Node table = tables.item(i);

                /* parsing out the table ksName */
                String ksName = XMLUtils.getAttributeValue(table, "Name");
                if (ksName == null)
                {
                    throw new ConfigurationException("Table name attribute is required");
                }
                if (ksName.equalsIgnoreCase(Table.SYSTEM_TABLE))
                {
                    throw new ConfigurationException("'system' is a reserved table name for Cassandra internals");
                }

                /* See which replica placement strategy to use */
                String replicaPlacementStrategyClassName = xmlUtils.getNodeValue("/Storage/Keyspaces/Keyspace[@Name='" + ksName + "']/ReplicaPlacementStrategy");
                if (replicaPlacementStrategyClassName == null)
                {
                    throw new ConfigurationException("Missing replicaplacementstrategy directive for " + ksName);
                }
                Class<? extends AbstractReplicationStrategy> repStratClass = null;
                try
                {
                    repStratClass = (Class<? extends AbstractReplicationStrategy>) Class.forName(replicaPlacementStrategyClassName);
                }
                catch (ClassNotFoundException e)
                {
                    throw new ConfigurationException("Invalid replicaplacementstrategy class " + replicaPlacementStrategyClassName);
                }

                /* Data replication factor */
                String replicationFactor = xmlUtils.getNodeValue("/Storage/Keyspaces/Keyspace[@Name='" + ksName + "']/ReplicationFactor");
                int repFact = -1;
                if (replicationFactor == null)
                    throw new ConfigurationException("Missing replicationfactor directory for keyspace " + ksName);
                else
                {
                    repFact = Integer.parseInt(replicationFactor);
                }

                /* end point snitch */
                String endPointSnitchClassName = xmlUtils.getNodeValue("/Storage/Keyspaces/Keyspace[@Name='" + ksName + "']/EndPointSnitch");
                if (endPointSnitchClassName == null)
                {
                    throw new ConfigurationException("Missing endpointsnitch directive for keyspace " + ksName);
                }
                IEndPointSnitch epSnitch = null;
                try
                {
                    Class cls = Class.forName(endPointSnitchClassName);
                    epSnitch = (IEndPointSnitch)cls.getConstructor().newInstance();
                }
                catch (ClassNotFoundException e)
                {
                    throw new ConfigurationException("Invalid endpointsnitch class " + endPointSnitchClassName);
                }
                catch (NoSuchMethodException e)
                {
                    throw new ConfigurationException("Invalid endpointsnitch class " + endPointSnitchClassName + " " + e.getMessage());
                }
                catch (InstantiationException e)
                {
                    throw new ConfigurationException("Invalid endpointsnitch class " + endPointSnitchClassName + " " + e.getMessage());
                }
                catch (IllegalAccessException e)
                {
                    throw new ConfigurationException("Invalid endpointsnitch class " + endPointSnitchClassName + " " + e.getMessage());
                }
                catch (InvocationTargetException e)
                {
                    throw new ConfigurationException("Invalid endpointsnitch class " + endPointSnitchClassName + " " + e.getMessage());
                }

                String xqlTable = "/Storage/Keyspaces/Keyspace[@Name='" + ksName + "']/";
                NodeList columnFamilies = xmlUtils.getRequestedNodeList(xqlTable + "ColumnFamily");

                KSMetaData meta = new KSMetaData(ksName, repStratClass, repFact, epSnitch);

                //NodeList columnFamilies = xmlUtils.getRequestedNodeList(table, "ColumnFamily");
                int size2 = columnFamilies.getLength();

                for ( int j = 0; j < size2; ++j )
                {
                    Node columnFamily = columnFamilies.item(j);
                    String tableName = ksName;
                    String cfName = XMLUtils.getAttributeValue(columnFamily, "Name");
                    if (cfName == null)
                    {
                        throw new ConfigurationException("ColumnFamily name attribute is required");
                    }
                    String xqlCF = xqlTable + "ColumnFamily[@Name='" + cfName + "']/";

                    // Parse out the column type
                    String rawColumnType = XMLUtils.getAttributeValue(columnFamily, "ColumnType");
                    String columnType = ColumnFamily.getColumnType(rawColumnType);
                    if (columnType == null)
                    {
                        throw new ConfigurationException("ColumnFamily " + cfName + " has invalid type " + rawColumnType);
                    }

                    if (XMLUtils.getAttributeValue(columnFamily, "ColumnSort") != null)
                    {
                        throw new ConfigurationException("ColumnSort is no longer an accepted attribute.  Use CompareWith instead.");
                    }

                    // Parse out the column comparator
                    AbstractType comparator = getComparator(columnFamily, "CompareWith");
                    AbstractType subcolumnComparator = null;
                    if (columnType.equals("Super"))
                    {
                        subcolumnComparator = getComparator(columnFamily, "CompareSubcolumnsWith");
                    }
                    else if (XMLUtils.getAttributeValue(columnFamily, "CompareSubcolumnsWith") != null)
                    {
                        throw new ConfigurationException("CompareSubcolumnsWith is only a valid attribute on super columnfamilies (not regular columnfamily " + cfName + ")");
                    }

                    double keysCachedFraction = 0.01d;
                    if ((value = XMLUtils.getAttributeValue(columnFamily, "KeysCachedFraction")) != null)
                    {
                        keysCachedFraction = Double.valueOf(value);
                    }

                    double rowCacheSize = 0;
                    if ((value = XMLUtils.getAttributeValue(columnFamily, "RowsCached")) != null)
                    {
                        if (value.endsWith("%"))
                        {
                            rowCacheSize = Double.valueOf(value.substring(0, value.length() - 1)) / 100;
                        }
                        else
                        {
                            rowCacheSize = Double.valueOf(value);
                        }
                    }

                    // Parse out user-specified logical names for the various dimensions
                    // of a the column family from the config.
                    String comment = xmlUtils.getNodeValue(xqlCF + "Comment");

                    // insert it into the table dictionary.
                    meta.cfMetaData.put(cfName, new CFMetaData(tableName, cfName, columnType, comparator, subcolumnComparator, comment, rowCacheSize, keysCachedFraction));
                }

                tables_.put(meta.name, meta);
            }
        }
        catch (XPathExpressionException e)
        {
            ConfigurationException ex = new ConfigurationException(e.getMessage());
            ex.initCause(e);
            throw ex;
        }
        catch (TransformerException e)
        {
            ConfigurationException ex = new ConfigurationException(e.getMessage());
            ex.initCause(e);
            throw ex;
        }
    }

    public static IAuthenticator getAuthenticator()
    {
        return authenticator;
    }

    public static boolean isThriftFramed()
    {
        return thriftFramed_;
    }

    private static AbstractType getComparator(Node columnFamily, String attr) throws ConfigurationException
//    throws ConfigurationException, TransformerException, NoSuchMethodException, InvocationTargetException, IllegalAccessException, InstantiationException
    {
        Class<? extends AbstractType> typeClass;
        String compareWith = null;
        try
        {
            compareWith = XMLUtils.getAttributeValue(columnFamily, attr);
        }
        catch (TransformerException e)
        {
            ConfigurationException ex = new ConfigurationException(e.getMessage());
            ex.initCause(e);
            throw ex;
        }
        if (compareWith == null)
        {
            typeClass = BytesType.class;
        }
        else
        {
            String className = compareWith.contains(".") ? compareWith : "org.apache.cassandra.db.marshal." + compareWith;
            try
            {
                typeClass = (Class<? extends AbstractType>)Class.forName(className);
            }
            catch (ClassNotFoundException e)
            {
                throw new ConfigurationException("Unable to load class " + className + " for " + attr + " attribute");
            }
        }
        try
        {
            return typeClass.getConstructor().newInstance();
        }
        catch (InstantiationException e)
        {
            ConfigurationException ex = new ConfigurationException(e.getMessage());
            ex.initCause(e);
            throw ex;
        }
        catch (IllegalAccessException e)
        {
            ConfigurationException ex = new ConfigurationException(e.getMessage());
            ex.initCause(e);
            throw ex;
        }
        catch (InvocationTargetException e)
        {
            ConfigurationException ex = new ConfigurationException(e.getMessage());
            ex.initCause(e);
            throw ex;
        }
        catch (NoSuchMethodException e)
        {
            ConfigurationException ex = new ConfigurationException(e.getMessage());
            ex.initCause(e);
            throw ex;
        }
    }

    /**
     * Creates all storage-related directories.
     * @throws IOException when a disk problem is encountered.
     */
    public static void createAllDirectories() throws IOException
    {
        try {
            if (dataFileDirectories_.length == 0)
            {
                throw new ConfigurationException("At least one DataFileDirectory must be specified");
            }
            for ( String dataFileDirectory : dataFileDirectories_ )
                FileUtils.createDirectory(dataFileDirectory);
            if (logFileDirectory_ == null)
            {
                throw new ConfigurationException("CommitLogDirectory must be specified");
            }
            FileUtils.createDirectory(logFileDirectory_);
        }
        catch (ConfigurationException ex) {
            logger_.error("Fatal error: " + ex.getMessage());
            System.err.println("Bad configuration; unable to start server");
            System.exit(1);
        }
        /* make sure we have a directory for each table */
        for (String dataFile : dataFileDirectories_)
        {
            FileUtils.createDirectory(dataFile + File.separator + Table.SYSTEM_TABLE);
            for (String table : tables_.keySet())
            {
                String oneDir = dataFile + File.separator + table;
                FileUtils.createDirectory(oneDir);

                // remove the deprecated streaming directory.
                File streamingDir = new File(oneDir, "stream");
                if (streamingDir.exists())
                    FileUtils.deleteDir(streamingDir);
            }
        }
    }

    /**
     * Create the metadata tables. This table has information about
     * the table name and the column families that make up the table.
     * Each column family also has an associated ID which is an int.
    */
    // TODO duplicating data b/t tablemetadata and CFMetaData is confusing and error-prone
    public static void storeMetadata() throws IOException
    {
        int cfId = 0;
        Set<String> tables = tables_.keySet();

        for (String table : tables)
        {
            Table.TableMetadata tmetadata = Table.TableMetadata.instance(table);
            if (tmetadata.isEmpty())
            {
                tmetadata = Table.TableMetadata.instance(table);
                /* Column families associated with this table */
                Map<String, CFMetaData> columnFamilies = tables_.get(table).cfMetaData;

                for (String columnFamily : columnFamilies.keySet())
                {
                    tmetadata.add(columnFamily, cfId++, DatabaseDescriptor.getColumnType(table, columnFamily));
                }
            }
        }
    }

    public static int getGcGraceInSeconds()
    {
        return gcGraceInSeconds_;
    }

    public static IPartitioner getPartitioner()
    {
        return partitioner_;
    }
    
    public static IEndPointSnitch getEndPointSnitch(String table)
    {
        return tables_.get(table).epSnitch;
    }

    public static Class<? extends AbstractReplicationStrategy> getReplicaPlacementStrategyClass(String table)
    {
        return tables_.get(table).repStratClass;
    }
    
    public static String getJobTrackerAddress()
    {
        return jobTrackerHost_;
    }
    
    public static int getColumnIndexSize()
    {
    	return columnIndexSizeInKB_ * 1024;
    }

    public static int getMemtableLifetimeMS()
    {
      return memtableLifetimeMs_;
    }

    public static String getInitialToken()
    {
      return initialToken_;
    }

    public static int getMemtableThroughput()
    {
      return memtableThroughput_;
    }

    public static double getMemtableOperations()
    {
      return memtableOperations_;
    }

    public static boolean getConsistencyCheck()
    {
      return doConsistencyCheck_;
    }

    public static String getClusterName()
    {
        return clusterName_;
    }

    public static String getConfigFileName() {
        return configFileName_;
    }

    public static String getJobJarLocation()
    {
        return jobJarFileLocation_;
    }
    
    public static Map<String, CFMetaData> getTableMetaData(String tableName)
    {
        assert tableName != null;
        KSMetaData ksm = tables_.get(tableName);
        assert ksm != null;
        return Collections.unmodifiableMap(ksm.cfMetaData);
    }

    /*
     * Given a table name & column family name, get the column family
     * meta data. If the table name or column family name is not valid
     * this function returns null.
     */
    public static CFMetaData getCFMetaData(String tableName, String cfName)
    {
        assert tableName != null;
        KSMetaData ksm = tables_.get(tableName);
        if (ksm == null)
            return null;
        return ksm.cfMetaData.get(cfName);
    }
    
    public static String getColumnType(String tableName, String cfName)
    {
        assert tableName != null;
        CFMetaData cfMetaData = getCFMetaData(tableName, cfName);
        
        if (cfMetaData == null)
            return null;
        return cfMetaData.columnType;
    }

    public static Set<String> getTables()
    {
        return tables_.keySet();
    }

    public static List<String> getNonSystemTables()
    {
        List<String> tables = new ArrayList<String>(tables_.keySet());
        tables.remove(Table.SYSTEM_TABLE);
        return Collections.unmodifiableList(tables);
    }

    public static int getStoragePort()
    {
        return storagePort_;
    }

    public static int getControlPort()
    {
        return controlPort_;
    }

    public static int getRpcPort()
    {
        return rpcPort_;
    }

    public static int getReplicationFactor(String table)
    {
        return tables_.get(table).replicationFactor;
    }

    public static int getQuorum(String table)
    {
        return (tables_.get(table).replicationFactor / 2) + 1;
    }

    public static long getRpcTimeout()
    {
        return rpcTimeoutInMillis_;
    }

    public static int getConsistencyThreads()
    {
        return consistencyThreads_;
    }

    public static int getConcurrentReaders()
    {
        return concurrentReaders_;
    }

    public static int getConcurrentWriters()
    {
        return concurrentWriters_;
    }

    public static String[] getAllDataFileLocations()
    {
        return dataFileDirectories_;
    }

    /**
     * Get a list of data directories for a given table
     * 
     * @param table name of the table.
     * 
     * @return an array of path to the data directories. 
     */
    public static String[] getAllDataFileLocationsForTable(String table)
    {
        String[] tableLocations = new String[dataFileDirectories_.length];

        for (int i = 0; i < dataFileDirectories_.length; i++)
        {
            tableLocations[i] = dataFileDirectories_[i] + File.separator + table;
        }

        return tableLocations;
    }

    public synchronized static String getNextAvailableDataLocation()
    {
        String dataFileDirectory = dataFileDirectories_[currentIndex_];
        currentIndex_ = (currentIndex_ + 1) % dataFileDirectories_.length;
        return dataFileDirectory;
    }

    public static String getLogFileLocation()
    {
        return logFileDirectory_;
    }

    public static Set<InetAddress> getSeeds()
    {
        return seeds_;
    }

    public static String getColumnFamilyType(String tableName, String cfName)
    {
        assert tableName != null;
        String cfType = getColumnType(tableName, cfName);
        if ( cfType == null )
            cfType = "Standard";
    	return cfType;
    }

    /*
     * Loop through all the disks to see which disk has the max free space
     * return the disk with max free space for compactions. If the size of the expected
     * compacted file is greater than the max disk space available return null, we cannot
     * do compaction in this case.
     */
    public static String getDataFileLocationForTable(String table, long expectedCompactedFileSize)
    {
      long maxFreeDisk = 0;
      int maxDiskIndex = 0;
      String dataFileDirectory = null;
      String[] dataDirectoryForTable = getAllDataFileLocationsForTable(table);

      for ( int i = 0 ; i < dataDirectoryForTable.length ; i++ )
      {
        File f = new File(dataDirectoryForTable[i]);
        if( maxFreeDisk < f.getUsableSpace())
        {
          maxFreeDisk = f.getUsableSpace();
          maxDiskIndex = i;
        }
      }
      // Load factor of 0.9 we do not want to use the entire disk that is too risky.
      maxFreeDisk = (long)(0.9 * maxFreeDisk);
      if( expectedCompactedFileSize < maxFreeDisk )
      {
        dataFileDirectory = dataDirectoryForTable[maxDiskIndex];
        currentIndex_ = (maxDiskIndex + 1 )%dataDirectoryForTable.length ;
      }
      else
      {
        currentIndex_ = maxDiskIndex;
      }
        return dataFileDirectory;
    }
    
    public static AbstractType getComparator(String tableName, String cfName)
    {
        assert tableName != null;
        CFMetaData cfmd = getCFMetaData(tableName, cfName);
        if (cfmd == null)
            throw new NullPointerException("Unknown ColumnFamily " + cfName + " in keyspace " + tableName);
        return cfmd.comparator;
    }

    public static AbstractType getSubComparator(String tableName, String cfName)
    {
        assert tableName != null;
        return getCFMetaData(tableName, cfName).subcolumnComparator;
    }

    public static double getKeysCachedFraction(String tableName, String columnFamilyName)
    {
        CFMetaData cfm = getCFMetaData(tableName, columnFamilyName);
        if (cfm == null)
            return 0.01d;
        return cfm.keysCachedFraction;
    }

    public static double getRowsCachedFraction(String tableName, String columnFamilyName)
    {
        CFMetaData cfm = getCFMetaData(tableName, columnFamilyName);
        if (cfm == null)
            return 0.01d;
        return cfm.rowCacheSize;
    }

    private static class ConfigurationException extends Exception
    {
        public ConfigurationException(String message)
        {
            super(message);
        }
    }

    public static InetAddress getListenAddress()
    {
        return listenAddress_;
    }
    
    public static InetAddress getRpcAddress()
    {
        return rpcAddress_;
    }

    public static double getCommitLogSyncBatchWindow()
    {
        return commitLogSyncBatchMS_;
    }

    public static int getCommitLogSyncPeriod() {
        return commitLogSyncPeriodMS_;
    }

    public static CommitLogSync getCommitLogSync()
    {
        return commitLogSync_;
    }

    public static DiskAccessMode getDiskAccessMode()
    {
        return diskAccessMode_;
    }

    public static DiskAccessMode getIndexAccessMode()
    {
        return indexAccessMode_;
    }

    public static double getFlushDataBufferSizeInMB()
    {
        return flushDataBufferSizeInMB_;
    }

    public static double getFlushIndexBufferSizeInMB()
    {
        return flushIndexBufferSizeInMB_;
    }

    public static int getIndexedReadBufferSizeInKB()
    {
        return columnIndexSizeInKB_;
    }

    public static int getSlicedReadBufferSizeInKB()
    {
        return slicedReadBufferSizeInKB_;
    }

    public static int getBMTThreshold()
    {
        return bmtThreshold_;
    }

    public static boolean isSnapshotBeforeCompaction()
    {
        return snapshotBeforeCompaction_;
    }

    public static boolean isAutoBootstrap()
    {
        return autoBootstrap_;
    }
}
