/**
 * Copyright (C) 2013 Red Hat, Inc. (jdcasey@commonjava.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.maven.galley.cache.infinispan;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.commonjava.cdi.util.weft.ContextSensitiveWeakHashMap;
import org.commonjava.cdi.util.weft.ThreadContext;
import org.commonjava.maven.galley.cache.partyline.PartyLineCacheProvider;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.spi.cache.CacheProvider;
import org.commonjava.maven.galley.spi.event.FileEventManager;
import org.commonjava.maven.galley.spi.io.PathGenerator;
import org.commonjava.maven.galley.spi.io.TransferDecorator;
import org.commonjava.maven.galley.util.PathUtils;
import org.commonjava.util.partyline.JoinableFileManager;
import org.commonjava.util.partyline.LockLevel;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryExpired;
import org.infinispan.notifications.cachelistener.event.CacheEntryExpiredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.SystemException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This cache provider provides the ability to write the backup artifacts to an external storage(NFS) which can be mounted
 * to local system as normal storage device, and meantime keep to cache these artifacts to local storage as usual. And a cache
 * to store the usage ownership of the external storage will be hosted in this provider. <br />
 * As this cache provider will use NFS as the distributed file caching, the NFS root directory is needed. If you want use this cache
 * provider in CDI environment, please don't forget to set the system property "galley.nfs.basedir" to specify this directory
 * as this provider will use it to get the nfs root directory by default. If you want to set this directory by manually,
 * use the parameterized constructor with the "nfsBaseDir" param.
 */
@SuppressWarnings( "unchecked" )
@Listener
public class FastLocalCacheProvider
        implements CacheProvider, CacheProvider.AdminView
{

    private static final String FAST_LOCAL_STREAMS = "fast-local-streams";

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    public static final String NFS_BASE_DIR_KEY = "galley.nfs.basedir";

    private String nfsBaseDir;

    // use weak key map to avoid the memory occupy for long time of the transfer
    private final Map<ConcreteResource, Transfer> transferCache = new ContextSensitiveWeakHashMap<>();

    private PartyLineCacheProvider plCacheProvider;

    // This NFS owner cache will be shared during nodes(indy?), it will record the which node is storing which file
    // in NFS. Used as <path, ip> cache to collect nfs ownership of the file storage
    private final CacheInstance<String, String> nfsOwnerCache;

    private FileEventManager fileEventManager;

    private TransferDecorator transferDecorator;

    private ExecutorService executor;

    private PathGenerator pathGenerator;

    private final JoinableFileManager fileManager = new JoinableFileManager();

    // Mapping key for ISPN transaction file counter in threadcontext, see #getFileCounter()
    private static final String ISPN_TX_FILE_COUNTER = "ISPN_TX_FILE_COUNTER";

    private final Map<Transfer, ReentrantLock> transferLocks = new ContextSensitiveWeakHashMap<>();

    private static final Long DEFAULT_WAIT_FOR_TRANSFER_LOCK_SECONDS = 600L;

    private static final Long DEFAULT_WAIT_FOR_TRANSFER_LOCK_MILLIS = DEFAULT_WAIT_FOR_TRANSFER_LOCK_SECONDS * 1000;

    private final CacheInstance<String, ConcreteResource> localFileCache;



    /**
     * Construct the FastLocalCacheProvider with the params. You can specify you own nfs base dir in this constructor.
     *
     * @param plCacheProvider - PartyLineCacheProvider to handle the local cache files
     * @param nfsUsageCache - ISPN cache to hold the nfs artifacts owner.
     * @param fileEventManager -
     * @param transferDecorator -
     * @param executor - The thread pool for executing reading task concurrently.
     * @param nfsBaseDir - The NFS system root dir to hold the artifacts
     */
    protected FastLocalCacheProvider( final PartyLineCacheProvider plCacheProvider,
                                      final CacheInstance<String, String> nfsUsageCache,
                                      final PathGenerator pathGenerator, final FileEventManager fileEventManager,
                                      final TransferDecorator transferDecorator, final ExecutorService executor,
                                      final String nfsBaseDir,
                                      final CacheInstance<String, ConcreteResource> localFileCache )
    {
        this.plCacheProvider = plCacheProvider;
        this.nfsOwnerCache = nfsUsageCache;
        this.pathGenerator = pathGenerator;
        this.fileEventManager = fileEventManager;
        this.transferDecorator = transferDecorator;
        this.executor = executor;
        this.localFileCache = localFileCache;
        setNfsBaseDir( nfsBaseDir );
        init();
    }

    private void checkNfsBaseDir(){
        if ( StringUtils.isEmpty( nfsBaseDir ) )
        {
            logger.debug( ">>>[galley] the nfs basedir is {}", nfsBaseDir );
            throw new IllegalArgumentException(
                    "[galley] FastLocalCacheProvider needs nfs directory to cache files, please set the parameter correctly or use system property \"galley.nfs.basedir\" first with your NFS root directory." );
        }
    }

    /**
     * Sets the nfs base dir. Note that if the nfsBaseDir is not valid(empty or not a directory), then will check the system property
     * "galley.nfs.basedir" to get the value again. If still not valid, will throw Exception
     *
     * @param nfsBaseDir -
     * @throws java.lang.IllegalArgumentException - the nfsBaseDir is not valid(empty or not a valid directory)
     */
    public void setNfsBaseDir( String nfsBaseDir )
    {
        this.nfsBaseDir = nfsBaseDir;
        if ( StringUtils.isBlank( this.nfsBaseDir )  )
        {
            logger.warn( "[galley] nfs basedir {} is not valid directory", this.nfsBaseDir );
            this.nfsBaseDir = System.getProperty( NFS_BASE_DIR_KEY );
        }
        checkNfsBaseDir();
    }

    @PostConstruct
    public void init()
    {
        if ( localFileCache != null )
        {
            localFileCache.execute( cache -> {
                cache.addListener( FastLocalCacheProvider.this );
                return null;
            } );
        }
        startReporting();
    }

    @PreDestroy
    public void destroy()
    {
        stopReporting();
    }

    @Override
    public boolean isFileBased()
    {
        return true;
    }

    @Override
    public File getDetachedFile( ConcreteResource resource )
    {
        File file = plCacheProvider.getDetachedFile( resource );
        if ( StringUtils.isNotBlank( nfsBaseDir ) && ( file == null || !file.exists() ) )
        {
            // if file not exists in cache dir, try NFS dir
            file = getNFSDetachedFile( resource );
        }
        return file;
    }

    File getNFSDetachedFile( ConcreteResource resource )
    {
        final File f = new File( getNFSFilePath( resource ) );
        if ( resource.isRoot() && !f.isDirectory() )
        {
            f.mkdirs();
        }
        return f;
    }

    @Override
    public void startReporting()
    {
        plCacheProvider.startReporting();
    }

    @Override
    public void stopReporting()
    {
        plCacheProvider.stopReporting();
    }

    @Override
    public void cleanupCurrentThread()
    {
        plCacheProvider.cleanupCurrentThread();

        ThreadContext streamHolder = ThreadContext.getContext( false );
        if ( streamHolder != null )
        {
            final String threadId = String.valueOf( Thread.currentThread().getId() );
            final Set<WeakReference<OutputStream>> streams = (Set<WeakReference<OutputStream>>) streamHolder.get( FAST_LOCAL_STREAMS );
            if ( streams != null && !streams.isEmpty() )
            {
                Iterator<WeakReference<OutputStream>> iter = streams.iterator();
                while ( iter.hasNext() )
                {
                    WeakReference<OutputStream> streamRef = iter.next();
                    IOUtils.closeQuietly( streamRef.get() );

                    iter.remove();
                }
            }
            streamHolder.remove( threadId );
        }
    }

    @Override
    public boolean isDirectory( ConcreteResource resource )
    {
        return getDetachedFile( resource ).isDirectory();
    }

    @Override
    public boolean isFile( ConcreteResource resource )
    {
        return getDetachedFile( resource ).isFile();
    }

    /**
     * For file reading, first will check if the local cache has the file there. If yes, will directly to read the local
     * cache. If no, then will check the NFS volume for the file, and will copy it to the local cache if found, then read
     * from the local cache again.
     *
     * @param resource - the resource will be read
     * @return - the input stream for further reading
     * @throws IOException
     */
    @Override
    public InputStream openInputStream( final ConcreteResource resource )
            throws IOException
    {
        final String pathKey = getKeyForResource( resource );

        // This lock is used to control the the local resource can be opened successfully finally when local resource missing
        // but NFS not, which means will do a NFS->local copy.
        final Object copyLock = new Object();

        // A flag to mark if the local resource can be open now or need to wait for the copy thread completes its work
        final AtomicBoolean canStreamOpen = new AtomicBoolean( false );

        // A second flag to indicate whether copyTask failed
        final AtomicBoolean copyExceOccurs = new AtomicBoolean( false );

        // This copy task is responsible for the NFS->local copy, and will be run in another thread,
        // which can use PartyLine concurrent read/write function on the local cache to boost
        // the i/o operation
        final Runnable copyNFSTask = () ->
        {
            InputStream nfsIn = null;
            OutputStream localOut = null;


            try
            {
                lockByISPN( nfsOwnerCache, resource, LockLevel.write );

                File nfsFile = getNFSDetachedFile( resource );
                if ( !nfsFile.exists() )
                {
                    logger.trace( "NFS file does not exist too." );
                    copyExceOccurs.set( true );
                    return;
                }
                nfsIn = new FileInputStream( nfsFile );
                localOut = plCacheProvider.openOutputStream( resource );
                canStreamOpen.set( true ); // set it ASAP so the readers can start reading before copy completes
                synchronized ( copyLock )
                {
                    copyLock.notifyAll();
                }
                IOUtils.copy( nfsIn, localOut );
                logger.trace( "NFS copy to local cache done." );
            }
            catch ( NotSupportedException | SystemException | IOException | InterruptedException e )
            {
                copyExceOccurs.set( true );
                if ( e instanceof IOException )
                {
                    final String errorMsg =
                            String.format( "[galley] got i/o error when doing the NFS->Local copy for resource %s",
                                           resource.toString() );
                    logger.warn( errorMsg, e );
                }
                else if ( e instanceof InterruptedException )
                {
                    final String errorMsg =
                            String.format( "[galley] got thread interrupted error for partyline file locking when doing the NFS->Local copy for resource %s",
                                           resource.toString() );
                    throw new IllegalStateException( errorMsg, e );
                }
                else
                {
                    final String errorMsg = String.format(
                            "[galley] Cache TransactionManager got error, locking key is %s, resource is %s", pathKey,
                            resource.toString() );
                    logger.error( errorMsg, e );
                    throw new IllegalStateException( errorMsg, e );
                }
            }
            finally
            {
                unlockByISPN( nfsOwnerCache, false, resource );

                IOUtils.closeQuietly( nfsIn );
                IOUtils.closeQuietly( localOut );
                cacheLocalFilePath( resource );
                synchronized ( copyLock )
                {
                    copyLock.notifyAll();
                }
            }
        };


        // This lock is used to control the concurrent operations on the resource, like concurrent delete and read/write.
        // Use "this" as lock is heavy, should think about use the transfer for the resource as the lock for each thread
        final AtomicReference<IOException> taskException = new AtomicReference<>();
        final InputStream stream = tryLockAnd( resource, DEFAULT_WAIT_FOR_TRANSFER_LOCK_SECONDS, TimeUnit.SECONDS, r -> {
            boolean localExisted = plCacheProvider.exists( r );

            if ( localExisted )
            {
                logger.trace( "local cache already exists, will directly get input stream from it." );
                try
                {
                    return plCacheProvider.openInputStream( r );
                }
                catch ( IOException e )
                {
                    taskException.set( e );
                    return null;
                }
            }
            else
            {
                logger.trace( "local cache does not exist, will start to copy from NFS cache" );
                executor.execute( copyNFSTask );
            }

            synchronized ( copyLock )
            {
                while ( !canStreamOpen.get() )
                {
                    if ( copyExceOccurs.get() )
                    {
                        return null;
                    }
                    try
                    {
                        copyLock.wait();
                    }
                    catch ( InterruptedException e )
                    {
                        logger.warn( "[galley] NFS copy thread is interrupted by other threads", e );
                    }
                }
                logger.trace( "the NFS->local copy completed, will get the input stream from local cache" );
                try
                {
                    return plCacheProvider.openInputStream( r );
                }
                catch ( IOException e )
                {
                    taskException.set( e );
                    return null;
                }
            }
        } );

        propagateException( taskException.get() );

        return stream;
    }

    /**
     * For file writing, will wrapping two output streams to caller - one for local cache file, another for nfs file -,
     * and the caller can write to these two streams in the meantime. <br />
     * For the local part, because it uses {@link org.commonjava.maven.galley.cache.partyline.PartyLineCacheProvider} as
     * i/o provider, this supports the R/W on the same resource in the meantime. For details, please see
     * {@link org.commonjava.maven.galley.cache.partyline.PartyLineCacheProvider}.
     *
     * @param resource - the resource will be read
     * @return - the output stream for further writing
     * @throws IOException
     */
    @Override
    public OutputStream openOutputStream( ConcreteResource resource )
            throws IOException
    {
        final DualOutputStreamsWrapper dualOutUpper;
        final String nodeIp = getCurrentNodeIp();
        final String pathKey = getKeyForResource( resource );
        final File nfsFile = getNFSDetachedFile( resource );

        final AtomicReference<IOException> taskException = new AtomicReference<>();
        final TransferLockTask<DualOutputStreamsWrapper> streamTransferLockTask = r -> {
            DualOutputStreamsWrapper dualOut = null;
            try
            {
                lockByISPN( nfsOwnerCache, resource, LockLevel.write );

                nfsOwnerCache.put( pathKey, nodeIp );

                logger.trace( "Start to get output stream from local cache through partyline to do join stream" );
                final OutputStream localOut = plCacheProvider.openOutputStream( resource );
                logger.trace( "The output stream from local cache through partyline is got successfully" );
                if ( !nfsFile.exists() && !nfsFile.isDirectory() )
                {
                    try
                    {
                        if ( !nfsFile.getParentFile().exists() )
                        {
                            nfsFile.getParentFile().mkdirs();
                        }
                        nfsFile.createNewFile();
                    }
                    catch ( IOException e )
                    {
                        logger.error( "[galley] New nfs file created not properly.", e );
                        throw e;
                    }
                }
                final OutputStream nfsOutputStream = new FileOutputStream( nfsFile );
                logger.trace( "The output stream from NFS is got successfully" );
                // will wrap the cache manager in stream wrapper, and let it do tx commit in stream close to make sure
                // the two streams writing's consistency.
                dualOut = new DualOutputStreamsWrapper( localOut, nfsOutputStream, nfsOwnerCache, pathKey, resource );

                if ( nfsOwnerCache.getLockOwner( pathKey ) != null )
                {
                    logger.trace( "[openOutputStream]ISPN locker for key {} with resource {} is {}", pathKey, resource,
                                  nfsOwnerCache.getLockOwner( pathKey ) );
                }

                ThreadContext streamHolder = ThreadContext.getContext( true );
                Set<WeakReference<OutputStream>> streams =
                        (Set<WeakReference<OutputStream>>) streamHolder.get( FAST_LOCAL_STREAMS );

                if ( streams == null )
                {
                    streams = new HashSet<>( 10 );
                }

                streams.add( new WeakReference<>( dualOut ) );
                streamHolder.put( FAST_LOCAL_STREAMS, streams );
            }
            catch ( NotSupportedException | SystemException | InterruptedException e )
            {
                logger.error( "[galley] Transaction error for nfs cache during file writing.", e );
                throw new IllegalStateException(
                        String.format( "[galley] Output stream for resource %s open failed.", resource.toString() ),
                        e );
            }
            catch ( IOException e )
            {
                taskException.set( e );
            }
            logger.trace( "The dual output stream wrapped and returned successfully" );
            return dualOut;
        };

        dualOutUpper = tryLockAnd( resource, DEFAULT_WAIT_FOR_TRANSFER_LOCK_SECONDS, TimeUnit.SECONDS, streamTransferLockTask );
        if ( taskException.get() != null )
        {
            throw taskException.get();
        }
        return dualOutUpper;
    }

    private void lockByISPN(final CacheInstance<String, String> cacheInstance, final ConcreteResource resource, final LockLevel level )
            throws SystemException, NotSupportedException, IOException, InterruptedException
    {
        //FIXME: This whole method is not thread-safe, especially for the lock state of the path, so the caller needs to take care

        // We need to think about the way of the ISPN lock and wait. If directly
        // use the nfsOwnerCache.lock but not consider if the lock has been acquired by another
        // thread, the ISPN lock will fail with a RuntimeException. So we need to let the
        // thread wait for the ISPN lock until it's released by the thread holds it. It's
        // like "tryLock" and "wait" of a thread lock.

        CacheInstance<String, String> cacheInst = cacheInstance;
        if ( cacheInst == null )
        {
            cacheInst = nfsOwnerCache;
        }

        final String path = getKeyForResource( resource );

        fileManager.lock( new File( path ), Long.MAX_VALUE, level );

        // Some consideration about the thread "re-entrant" for waiting here. If it is the same
        // thread, will not wait.
        waitForISPNLock( resource, cacheInst.isLocked( path ), DEFAULT_WAIT_FOR_TRANSFER_LOCK_MILLIS );

        if ( cacheInst.getTransactionStatus() == Status.STATUS_NO_TRANSACTION )
        {
            cacheInst.beginTransaction();
            logger.trace( "Transaction started for path {} with resource {}", path, resource );
        }

        if ( !cacheInst.isLocked( path ) && isTxActive( cacheInstance ) )
        {
            cacheInst.lock( path );
            // Increment a file counter to notify that there is a file operation with ISPN lock now in this ISPN TX
            final int counter = getFileCounter().incrementAndGet();
            logger.trace( "ISPN locked once more, path: {}, resource {}, file counter: {}", path, resource, counter );
        }
    }

    private void unlockByISPN( final CacheInstance<String, String> cacheInstance, final boolean needCommit,
                               final ConcreteResource resource )
    {
        final File resourcePath;
        final String path;
        try
        {
            path = getKeyForResource( resource );
            resourcePath = new File( path );
            fileManager.unlock( resourcePath );
        }
        catch ( IOException e )
        {
            final String errorMsg =
                    String.format( "Got i/o error when doing the parytyline file unlocking for resource %s",
                                   resource.toString() );
            throw new IllegalStateException( errorMsg, e );
        }

        final int lockCount = fileManager.getContextLockCount( resourcePath );

        logger.trace( "Unlocked file lock for path {}, current lock count is {}", resourcePath, lockCount );

        if ( lockCount == 0 )
        {
            CacheInstance<String, String> cacheInst = cacheInstance;
            if ( cacheInst == null )
            {
                cacheInst = nfsOwnerCache;
            }

            try
            {
                // The lock and unlock operation is only useful in an active Transaction of ISPN.
                if ( isTxActive( cacheInstance ) )
                {
                    // Decrease the file counter to notify that a file operation ended and need to be unlocked from ISPN. If
                    // counter is still not 0, means this ISPN TX still has other file operations in, so should only unlock
                    // this file, but do not end the whole ISPN TX.
                    Integer count = 0;
                    if ( cacheInst.isLocked( path ) )
                    {
                        logger.trace( "Unlocking ISPN lock for key: {} with resource: {}", path, resource );
                        cacheInst.unlock( path );
                        logger.trace( "Lock status after unlocking: {}", cacheInst.isLocked( path ) );
                        count = getFileCounter().decrementAndGet();
                        logger.trace( "Unlocked ISPN file count for path {}, current file count is {}", resourcePath,
                                      count );
                    }
                    if ( count == 0 )
                    {
                        if ( needCommit )
                        {
                            try
                            {
                                {
                                    cacheInst.commit();
                                    logger.trace( "Transaction committed for path {} with resource {}", path,
                                                  resource );
                                    return;
                                }
                            }
                            catch ( SystemException | RollbackException | HeuristicMixedException | HeuristicRollbackException e )
                            {
                                logger.error( "[galley] Transaction commit error for nfs cache during file operation.",
                                              e );
                            }
                        }
                        // if commit failed, we should do rollback action to make sure the lock has been released
                        try
                        {
                            cacheInst.rollback();
                            logger.trace( "Transaction rollbacked for path {} with resource {}", path, resource );
                        }
                        catch ( SystemException se )
                        {
                            final String errorMsg =
                                    "[galley] Transaction rollback error for nfs cache during file operation.";
                            logger.error( errorMsg, se );
                            throw new IllegalStateException( errorMsg, se );
                        }

                    }
                }
            }
            catch ( SystemException e )
            {
                logger.error( "[galley] Transaction status getting error for nfs cache during file operation.", e );
            }
        }
    }

    private boolean isTxActive( CacheInstance<String, String> cacheInstance )
            throws SystemException
    {
        final int[] ACTIVE_STATUS = new int[] { Status.STATUS_ACTIVE, Status.STATUS_COMMITTING, Status.STATUS_PREPARING,
                Status.STATUS_PREPARED, Status.STATUS_ROLLING_BACK };
        boolean isActive = false;
        for ( int status : ACTIVE_STATUS )
        {
            if ( cacheInstance.getTransactionStatus() == status )
            {
                isActive = true;
                break;
            }
        }
        return isActive;
    }

    // This file counter is used to solve the problem of multi file operations in ISPN TX. Sometimes in one single ISPN TX,
    // it may includes more than one file operations. If we don't control the ISPN lock of each operation separately and just use
    // tx.commit/rollback, it may bring ISPN TX in some weird state. This file counter is more like a thread re-entrant feature for
    // ISPN single TX for different files.
    // For example, a thread can lock foo/bar/ and then start writing foo/bar/foo-bar-1.0.pom. When this writing is not finished(closed),
    // this thread will also start writing foor/bar/foo-bar-1.0.pom.sha1, so we should let the thread can lock foo/bar/ again as re-entrant
    // here with this file counter. When finished one of these two writing, do not let the ISPN TX to commit(rollback), just decrease the
    // counter here, and we should commit(rollback) the TX when all writing of these two done(file counter is 0 here)
    private synchronized AtomicInteger getFileCounter()
    {
        ThreadContext streamHolder = ThreadContext.getContext( true );
        streamHolder.putIfAbsent( ISPN_TX_FILE_COUNTER, new AtomicInteger( 0 ) );
        return (AtomicInteger) streamHolder.get( ISPN_TX_FILE_COUNTER );
    }

    @Override
    public boolean exists( ConcreteResource resource )
    {
        return plCacheProvider.exists( resource ) || getNFSDetachedFile( resource ).exists();
    }

    @Override
    public void copy( ConcreteResource from, ConcreteResource to )
            throws IOException
    {
        final String fromNFSPath = getKeyForResource( from );
        final String toNFSPath = getKeyForResource( to );
        //FIXME: there is no good solution here for thread locking as there are two resource needs to be locked. If handled not correctly, will cause dead lock
        InputStream nfsFrom = null;
        OutputStream nfsTo = null;
        try
        {
            //FIXME: need to think about this lock of the re-entrant way and ISPN lock wait
            nfsOwnerCache.beginTransaction();
            nfsOwnerCache.lock( fromNFSPath, toNFSPath );
            plCacheProvider.copy( from, to );
            nfsFrom = new FileInputStream( getNFSDetachedFile( from ) );
            File nfsToFile = getNFSDetachedFile( to );
            if ( !nfsToFile.exists() && !nfsToFile.isDirectory() )
            {
                if ( !nfsToFile.getParentFile().exists() )
                {
                    nfsToFile.getParentFile().mkdirs();
                }
                try
                {
                    nfsToFile.createNewFile();
                }
                catch ( IOException e )
                {
                    logger.error( "[galley] New nfs file created not properly.", e );
                }
            }
            nfsTo = new FileOutputStream( nfsToFile );
            IOUtils.copy( nfsFrom, nfsTo );
            //FIXME: need to use put?
            nfsOwnerCache.putIfAbsent( toNFSPath, getCurrentNodeIp() );
            nfsOwnerCache.commit();
        }
        catch ( NotSupportedException | SystemException | RollbackException | HeuristicMixedException | HeuristicRollbackException e )
        {
            logger.error( "[galley] Transaction error for nfs cache during file copying.", e );
            try
            {
                nfsOwnerCache.rollback();
            }
            catch ( SystemException se )
            {
                final String errorMsg = "[galley] Transaction rollback error for nfs cache during file copying.";
                logger.error( errorMsg, se );
                throw new IllegalStateException( errorMsg, se );
            }
        }
        finally
        {
            IOUtils.closeQuietly( nfsFrom );
            IOUtils.closeQuietly( nfsTo );
        }

    }

    @Override
    public String getFilePath( final ConcreteResource resource )
    {
        String dir = resource.getLocation()
                             .getAttribute( Location.ATTR_ALT_STORAGE_LOCATION, String.class );

        return dir != null ?
                PathUtils.normalize( dir, pathGenerator.getFilePath( resource ) ) :
                getNFSFilePath( resource );
    }

    private String getNFSFilePath(final ConcreteResource resource){
        return PathUtils.normalize( nfsBaseDir, pathGenerator.getFilePath( resource ) );
    }

    @Override
    public boolean delete( ConcreteResource resource )
            throws IOException
    {
        final File nfsFile = getNFSDetachedFile( resource );
        final String pathKey = getKeyForPath( nfsFile.getCanonicalPath() );

        final AtomicReference<Exception> taskException = new AtomicReference<>();
        final Boolean deleteResult = tryLockAnd( resource, DEFAULT_WAIT_FOR_TRANSFER_LOCK_SECONDS, TimeUnit.SECONDS, r->
        {
            boolean localDeleted = false;
            try
            {
                // must make sure the local file is not in reading/writing status
                if ( !plCacheProvider.isWriteLocked( resource ) && !plCacheProvider.isReadLocked( resource ) )
                {
                    logger.debug( "[galley] Local cache file is not locked, will be deleted now." );
                    localDeleted = plCacheProvider.delete( resource );
                }
                else
                {
                    logger.warn(
                            "Resource {} is locked by other threads for waiting and writing, can not be deleted now",
                            resource );
                }
                if ( !localDeleted )
                {
                    // if local deletion not success, no need to delete NFS to keep data consistency
                    logger.info( "local file deletion failed for {}", resource );
                    return false;
                }
                lockByISPN( nfsOwnerCache, resource, LockLevel.delete );
                nfsOwnerCache.remove( pathKey );
                final boolean nfsDeleted = nfsFile.delete();
                if ( !nfsDeleted )
                {
                    logger.info( "nfs file deletion failed for {}", nfsFile );
                }
                return nfsDeleted;
            }
            catch ( NotSupportedException | SystemException | InterruptedException e  )
            {
                final String errorMsg = String.format( "[galley] Cache TransactionManager got error, locking key is %s", pathKey );
                logger.error( errorMsg, e );
                taskException.set( e );
            }
            catch ( IOException e )
            {
                taskException.set( e );
            }
            finally
            {
                if ( localDeleted )
                {
                    logger.info( "Local file deleted and ISPN lock started for {}, need to release ISPN lock", resource );
                    unlockByISPN( nfsOwnerCache, false, resource );
                    localFileCache.remove( resource.getPath() );
                }
            }
            return false;
        });

        propagateException( taskException.get() );

        return deleteResult == null ? false : deleteResult;
    }

    @Override
    public String[] list( ConcreteResource resource )
    {
        // Only focus on NFS location
        return getNFSDetachedFile( resource ).list();
    }

    @Override
    public void mkdirs( ConcreteResource resource )
            throws IOException
    {
        final String pathKey = getKeyForResource( resource );
        try
        {
            lockByISPN( nfsOwnerCache, resource, LockLevel.write );
            getDetachedFile( resource ).mkdirs();
        }
        catch ( NotSupportedException | SystemException | InterruptedException e )
        {
            final String errorMsg =
                    String.format( "[galley] Cache TransactionManager got error, locking key is %s", pathKey );
            logger.error( errorMsg, e );
            throw new IllegalStateException( errorMsg, e );
        }
        finally
        {
            unlockByISPN( nfsOwnerCache, false, resource );
        }
    }

    @Deprecated
    @Override
    public void createFile( ConcreteResource resource )
            throws IOException
    {
        final String pathKey = getKeyForResource( resource );
        try
        {
            lockByISPN( nfsOwnerCache, resource, LockLevel.write );
            final File nfsFile = getNFSDetachedFile( resource );
            if ( !nfsFile.exists() )
            {
                nfsFile.getParentFile().mkdirs();
                nfsFile.createNewFile();
            }
        }
        catch ( NotSupportedException | SystemException | InterruptedException e )
        {
            final String errorMsg =
                    String.format( "[galley] Cache TransactionManager got error, locking key is %s", pathKey );
            logger.error( errorMsg, e );
            throw new IllegalStateException( errorMsg, e );
        }
        finally
        {
            unlockByISPN( nfsOwnerCache, false, resource );
        }
    }

    @Deprecated
    @Override
    public void createAlias( ConcreteResource from, ConcreteResource to )
            throws IOException
    {
        // if the download landed in a different repository, copy it to the current one for
        // completeness..., and both in local and nfs sides
        final Location fromKey = from.getLocation();
        final Location toKey = to.getLocation();
        final String fromPath = from.getPath();
        final String toPath = to.getPath();

        if ( fromKey != null && toKey != null && !fromKey.equals( toKey ) && fromPath != null && toPath != null
                && !fromPath.equals( toPath ) )
        {
            copy( from, to );
        }
    }

    @Override
    public synchronized Transfer getTransfer( final ConcreteResource resource )
    {
        Transfer t = transferCache.get( resource );
        if ( t == null )
        {
            t = new Transfer( resource, this, fileEventManager, transferDecorator );
            transferCache.put( new ConcreteResource( resource.getLocation(), resource.getPath() ), t );
        }

        return t;
    }

    @Override
    public synchronized void clearTransferCache()
    {
        transferCache.clear();
    }

    @Override
    public long length( ConcreteResource resource )
    {
        final File file = getNFSDetachedFilePrimarily( resource );
        return file == null ? 0 : file.length();
    }

    @Override
    public long lastModified( ConcreteResource resource )
    {
        return getNFSDetachedFilePrimarily( resource ).lastModified();
    }

    /**
     * Something that will happen here is: if a file (maybe big) is in process of NFS->local, but another user starts to request this file.
     * As this file is not yet in local, it will return a wrong file attribute(like length or lastModified) to the end user, which will
     * result error response. So here we supply a flag to choose NFS primarily to use NFS file to get the file attr, which is more stable reference.
     *
     * @param resource
     * @return
     */
    private File getNFSDetachedFilePrimarily( ConcreteResource resource){
        File file = null;
        if ( StringUtils.isNotBlank( nfsBaseDir ) )
        {
            file = getNFSDetachedFile( resource );
        }

        if ( file == null || !file.exists() )
        {
            file = getDetachedFile( resource );
        }

        return file;

    }

    @Override
    public boolean isReadLocked( ConcreteResource resource )
    {
        try
        {
            //To avoid deadlock, here use a lock with timeout, if timeout happened, will throw exception
            AtomicReference<IOException> taskException = new AtomicReference<>();
            final Boolean result = tryLockAnd( resource, DEFAULT_WAIT_FOR_TRANSFER_LOCK_SECONDS, TimeUnit.SECONDS, r -> {
                try
                {
                    final String cacheKey = getKeyForResource( resource );
                    final boolean isFileReadLocked = plCacheProvider.isReadLocked( resource );
                    final boolean isISPNLocked = nfsOwnerCache.isLocked( cacheKey );
                    logger.trace(
                            "The read lock status: resource locked: {}, ISPN locked: {}, lockKey: {}, Resource: {}",
                            isFileReadLocked, isISPNLocked, cacheKey, resource );
                    return isFileReadLocked || isISPNLocked;
                }
                catch ( IOException e )
                {
                    taskException.set( e );
                    return false;
                }
            } );
            propagateException( taskException.get() );

            return result == null ? false : result;
        }
        catch ( IOException e )
        {
            final String errorMsg = String.format( "[galley] When get NFS cache key for resource: %s, got I/O error.",
                                                   resource.toString() );
            logger.error( errorMsg, e );
            throw new IllegalStateException( errorMsg, e );
        }
    }

    @Override
    public boolean isWriteLocked( ConcreteResource resource )
    {
        try
        {
            //To avoid deadlock, here use a lock with timeout, if timeout happened, will throw exception
            AtomicReference<IOException> taskException = new AtomicReference<>();
            final Boolean result = tryLockAnd( resource, DEFAULT_WAIT_FOR_TRANSFER_LOCK_SECONDS, TimeUnit.SECONDS, r -> {
                try
                {
                    final String cacheKey = getKeyForResource( resource );
                    final boolean isFileWriteLocked = plCacheProvider.isWriteLocked( resource );
                    final boolean isISPNLocked = nfsOwnerCache.isLocked( getKeyForResource( resource ) );
                    logger.trace(
                            "The write lock status: resource locked: {}, ISPN locked: {}, lock key: {}, Resource: {}",
                            isFileWriteLocked, isISPNLocked, cacheKey, resource );
                    return isFileWriteLocked || isISPNLocked;
                }
                catch ( IOException e )
                {
                    taskException.set( e );
                    return false;
                }
            } );
            propagateException( taskException.get() );

            return result == null ? false : result;
        }
        catch ( IOException e )
        {
            final String errorMsg = String.format( "[galley] When get NFS cache key for resource: %s, got I/O error.",
                                                   resource.toString() );
            logger.error( errorMsg, e );
            throw new IllegalStateException( errorMsg, e );
        }
    }

    @Override
    public void unlockRead( ConcreteResource resource )
    {
        // Not supported yet
    }

    @Override
    public void unlockWrite( ConcreteResource resource )
    {
        // Not supported yet
    }

    @Override
    public void lockRead( ConcreteResource resource )
    {
        // Not supported yet
    }

    @Override
    public void lockWrite( ConcreteResource resource )
    {
        // Not supported yet
    }

    @Override
    public void waitForReadUnlock( ConcreteResource resource )
    {
        //To avoid deadlock, here use a lock with timeout, if timeout happened, will throw exception
        try
        {
            final AtomicReference<IOException> taskException = new AtomicReference<>();
            tryLockAnd( resource, DEFAULT_WAIT_FOR_TRANSFER_LOCK_SECONDS, TimeUnit.SECONDS, r -> {
                plCacheProvider.waitForReadUnlock( resource );
                try
                {
                    waitForISPNLock( resource, isReadLocked( resource ), DEFAULT_WAIT_FOR_TRANSFER_LOCK_MILLIS);
                }
                catch ( IOException e )
                {
                    taskException.set( e );
                }
                return null;
            } );
            propagateException( taskException.get() );
        }
        catch ( IOException e )
        {
            final String errorMsg = String.format( "[galley] When wait for read lock of resource: %s, got I/O error.",
                                                   resource.toString() );
            logger.error( errorMsg, e );
            throw new IllegalStateException( errorMsg, e );
        }
    }

    @Override
    public AdminView asAdminView()
    {
        return this;
    }

    @Override
    public void waitForWriteUnlock( ConcreteResource resource )
    {
        //To avoid deadlock, here use a lock with timeout, if timeout happened, will throw exception
        try
        {
            final AtomicReference<IOException> taskException = new AtomicReference<>();
            tryLockAnd( resource, DEFAULT_WAIT_FOR_TRANSFER_LOCK_SECONDS, TimeUnit.SECONDS, r -> {
                plCacheProvider.waitForWriteUnlock( resource );
                try
                {
                    waitForISPNLock( resource, isWriteLocked( resource ), DEFAULT_WAIT_FOR_TRANSFER_LOCK_MILLIS );
                }
                catch ( IOException e )
                {
                    taskException.set( e );
                }
                return null;
            } );
            propagateException( taskException.get() );
        }
        catch ( IOException e )
        {
            final String errorMsg = String.format( "[galley] When wait for read lock of resource: %s, got I/O error.",
                                                   resource.toString() );
            logger.error( errorMsg, e );
            throw new IllegalStateException( errorMsg, e );
        }
    }

    /**
     * Waits for ISPN lock of resource to release the lock. You can specify a timeout (in milliseconds) to let this waiting
     * can be timeout. Not that this method only wait for different threads which holds the locker of the path, if it is same
     * thread, will directly go through it for following oeprations
     *
     * @param resource the resource can supply the key of the ISPN locker
     * @param locked only when this param is true, this method will try to do waiting.
     * @param timeout a timeout (in milliseconds) to let the wait not blocked forever.
     * @throws IOException when timeout, a IOException will be thrown to notify
     */
    private void waitForISPNLock( ConcreteResource resource, boolean locked, long timeout )
            throws IOException
    {
        final String path;
        try
        {
            path = getKeyForResource( resource );
        }
        catch ( IOException e )
        {
            final String errorMsg =
                    String.format( "[galley] When get NFS cache key for resource: %s, got I/O error.", resource.toString() );
            logger.error( errorMsg, e );
            throw new IllegalStateException( errorMsg, e );
        }

        if ( fileManager.isLockedByCurrentThread( new File( path ) ) )
        {
            logger.trace( "Processing in same thread, will not wait for ISPN lock to make it re-entrant" );
            return;
        }

        final boolean needTimeout = timeout > 0;
        final long WAIT_INTERVAL = 1000;
        long timeDuration = 0;
        while ( locked )
        {
            // Use ISPN lock owner for resource to wait until lock is released. Note that if the lock has no owner,
            // means lock has been released
            final Object owner = nfsOwnerCache.getLockOwner( path );
            if ( owner == null )
            {
                break;
            }

            logger.trace(
                    "ISPN lock still not released. ISPN lock key:{}, locker: {}, operation path: {}. Waiting for 1 seconds",
                    path, owner, resource );

            if ( needTimeout && timeDuration > timeout )
            {
                throw new IOException( String.format(
                        "ISPN lock timeout after %d Milliseconds! The ISPN lock owner is %s, and lock key is %s",
                        timeout, owner, path ) );
            }
            else
            {
               try
                {
                    synchronized ( owner )
                    {
                        owner.wait( WAIT_INTERVAL );
                        timeDuration += WAIT_INTERVAL;
                    }
                }
                catch ( final InterruptedException e )
                {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private String getCurrentNodeIp()
            throws SocketException
    {

        final Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();

        while ( nis.hasMoreElements() )
        {
            final NetworkInterface ni = nis.nextElement();
            final Enumeration<InetAddress> ips = ni.getInetAddresses();
            while ( ips.hasMoreElements() )
            {
                final InetAddress ip = ips.nextElement();
                if ( ip instanceof Inet4Address && ip.isSiteLocalAddress() )
                {
                    return ip.getHostAddress();
                }
            }
        }

        throw new IllegalStateException( "[galley] IP not found." );
    }

    private String getKeyForResource( ConcreteResource resource ) throws IOException
    {
        File nfsFile = getNFSDetachedFile( resource );
        // Need the key as a parent folder level to lock all files I/O with some derivative files like checksum files
        return getKeyForPath(
                nfsFile.isDirectory() ? nfsFile.getCanonicalPath() : nfsFile.getParentFile().getCanonicalPath() );

    }

    private String getKeyForPath( String path )
    {
        //TODO: will directly return path now, may change some other way future(like digesting?)
        return path;
    }

    private ReentrantLock getTransferLock( final ConcreteResource resource )
    {
        final Transfer transfer = getTransfer( resource );
        return transferLocks.computeIfAbsent( transfer, tran -> new ReentrantLock() );
    }

    private <K> K tryLockAnd( ConcreteResource resource, long timeout, TimeUnit unit, TransferLockTask<K> task ) throws IOException
    {
        ReentrantLock lock = getTransferLock( resource );
        boolean locked = false;
        try
        {
            if ( timeout > 0 )
            {
                locked = lock.tryLock( timeout, unit );
                if ( locked )
                {
                    return task.execute( resource );
                }
                else
                {
                    throw new IOException(
                            String.format( "Did not get lock for resource %s in %d %s, timeout happened.", resource,
                                           timeout, unit.toString() ) );
                }
            }
            else
            {
                lock.lockInterruptibly();
                return task.execute( resource );
            }

        }
        catch ( InterruptedException e )
        {
            logger.warn( "Interrupted for the transfer lock with resource: {}", resource );
            return null;
        }
        finally
        {
            if ( timeout <= 0 || locked )
            {
                lock.unlock();
            }
        }
    }

    private void propagateException( Exception e )
            throws IOException
    {
        if ( e != null )
        {
            if ( e instanceof RuntimeException )
            {
                throw (RuntimeException) e;
            }
            if ( e instanceof IOException )
            {
                throw (IOException) e;
            }
        }
    }

    private void cacheLocalFilePath( final ConcreteResource resource )
    {
        if ( plCacheProvider.exists( resource ) )
        {
            localFileCache.put( resource.getPath(), resource );
        }
    }

    @CacheEntryExpired
    public void localFileExpired( CacheEntryExpiredEvent<String, ConcreteResource> e )
    {
        final Logger logger = LoggerFactory.getLogger( this.getClass() );
        if ( e == null )
        {
            logger.error( "[FATAL]The infinispan cache expired event for indy schedule manager is null.",
                          new NullPointerException( "CacheEntryExpiredEvent is null" ) );
            return;
        }

        if ( !e.isPre() )
        {
            final String localFilePath = e.getKey();
            if ( StringUtils.isNotBlank( localFilePath ) )
            {
                final ConcreteResource resource = e.getValue();
                try
                {
                    plCacheProvider.delete( resource );
                }
                catch ( IOException ex )
                {
                    logger.error( String.format( "Cannot delete local file %s for expiration.", resource ), ex );
                }
            }
        }
    }

    /**
     * A output stream wrapper to let the stream writing to dual output stream
     */
    private final class DualOutputStreamsWrapper
            extends OutputStream
    {

        private final OutputStream out1;

        private final OutputStream out2;

        private final CacheInstance<String,String> cacheInstance;

        private boolean closed = false;

        private final String cacheKey;

        private final ConcreteResource resource;

        public DualOutputStreamsWrapper( final OutputStream out1, final OutputStream out2,
                                         final CacheInstance<String, String> cacheInstance, final String cacheKey, final ConcreteResource resource )
        {
            if ( cacheInstance == null )
            {
                throw new NullPointerException( "Cache instance cannot be null." );
            }

            if ( out1 == null || out2 == null )
            {
                throw new NullPointerException( "Output streams cannot be null: (stream1: " + out1 + " / stream2: " + out2 + ")" );
            }

            this.out1 = out1;
            this.out2 = out2;
            this.cacheInstance = cacheInstance;
            this.cacheKey = cacheKey;
            this.resource = resource;
        }

        @Override
        public void write( int b )
                throws IOException
        {
            out1.write( b );
            out2.write( b );
        }

        @Override
        public void write( byte b[] )
                throws IOException
        {
            write( b, 0, b.length );
        }

        @Override
        public void write( byte b[], int off, int len )
                throws IOException
        {
            out1.write( b, off, len );
            out2.write( b, off, len );
        }

        @Override
        public void flush()
                throws IOException
        {
            out1.flush();
            out2.flush();
        }

        @Override
        public void close()
                throws IOException
        {
            // To resolve "Double-close" issue, add this closed flag for the "real closed" recognition
            final Logger logger = FastLocalCacheProvider.this.logger;
            if ( closed )
            {
                logger.trace( "The DualOutputStream {} already closed. path: {}, resource: {}", this, cacheKey, resource );
                // If still ISPN locked, we should unlock it to avoid "lock-never-released"
                if ( cacheInstance.isLocked( cacheKey ) )
                {
                    unlockByISPN( cacheInstance, false, resource );
                }
                return;
            }

            Object lockOwner = cacheInstance.getLockOwner( cacheKey );
            logger.trace( "ISPN lock released before ISPN trasaction for key {} with resource {}? {}", cacheKey,
                          resource, lockOwner == null ? "Yes" : "No" );
            if ( lockOwner != null )
            {
                logger.trace( "[DualOutputStream.close]ISPN locker for key {} with resource {} is {}",
                              cacheKey, resource, lockOwner );
            }

            try
            {
                unlockByISPN( cacheInstance, true, resource );

                // To avoid ISPN lock not released correctly, should consider the real closed case after the lock released successfully
                if ( !closed )
                {
                    closed = true;
                }
            }
            finally
            {
                // For safe, we should always let the stream closed, even if the transaction failed.
                IOUtils.closeQuietly( out1 );
                IOUtils.closeQuietly( out2 );
                cacheLocalFilePath( resource );

                logger.trace( "ISPN lock released after ISPN trasaction for key {} with resource {}? {}", cacheKey, resource,
                              cacheInstance.getLockOwner( cacheKey ) == null ? "Yes" : "No" );
            }
        }
    }


    private interface TransferLockTask<T>
    {
        T execute( ConcreteResource resource );
    }

}
