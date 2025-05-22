package org.infinispan;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.infinispan.client.hotrod.DataFormat;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.commons.marshall.IdentityMarshaller;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.query.remote.impl.persistence.PersistenceContextInitializerImpl;

import io.reactivex.rxjava3.core.Flowable;
import picocli.CommandLine;

/**
 * Application that can be used to "upload" from a previous cache that has SoftIndexFileStore data directory still intact.
 * This will use octet stream encoding only as to not require user classes to be present in the classpath.
 * <p>
 * The remote client will solely be configured using <i>hotrod-client.properties</i> in the classpath. If a media type
 * is configured in this properties file it will be ignored.
 */
public class SoftIndexFileStoreRestore implements Runnable {

   @CommandLine.Parameters(paramLabel = "CACHE", description = "The name of the cache - should match the name in the data directory and remote server (must be defined on remote server)")
   String cacheName;

   @CommandLine.Parameters(paramLabel = "DIR", description = "The directory where cache persistence is - does not include cache name")
   File dataDir;

   @CommandLine.Option(names = "-d", description = "Name of the store data directory inside the cache named directory. Use this if your previous configuration defined an explicit data path")
   String dataDirName;

   @CommandLine.Option(names = "-i", description = "Name of the store index directory inside the cache named directory. Use this if your previous configuration defined an explicit index path")
   String indexDirName;

   @CommandLine.Option(names = "-f", defaultValue = "5", description = "How often message should be printed showing index or insert progress in seconds. Default is 5 seconds")
   int updateFrequency;

   @CommandLine.Option(names = "-r", defaultValue = "false", description = "Whether the index should only rebuilt and skip writing the contents to a remote server.")
   boolean rebuildIndexOnly;

   @CommandLine.Option(names = "-p", defaultValue = "16", description = "How many parallel remote puts can be inflight. Only used if rebuildIndexOnly is not set.")
   int parallelInserts;


   public static void main(String[] args) {
      int exitCode = new CommandLine(new SoftIndexFileStoreRestore()).execute(args);
      System.exit(exitCode);
   }

   @Override
   public void run() {
      System.out.println("\n\n\n   ********************************  \n\n\n");
      System.out.println("Hello.  This will load contents from cache: " + cacheName +
            " located in data directory: " + dataDir.getAbsolutePath());
      System.out.println("Data directory name is: " + dataDirName + " and Index directory name is: " + indexDirName);
      if (rebuildIndexOnly) {
         System.out.println("Skipping remote transfer and just rebuilding index if required");
      } else {
         System.out.println("Using hotrod-client.properties for setup of client located in base classpath of project");
      }

      GlobalConfigurationBuilder globalConfigurationBuilder = new GlobalConfigurationBuilder();
      globalConfigurationBuilder.serialization()
            // Query wraps byte arrays we need to be able to unmarshall that as well
            .addContextInitializer(new PersistenceContextInitializerImpl())
            // Don't let the store unmarshall anything - just keep the raw bytes
            .marshaller(new IdentityMarshaller());
      try (RemoteCacheManager remoteCacheManager = rebuildIndexOnly ? null : new RemoteCacheManager();
           EmbeddedCacheManager cacheManager = new DefaultCacheManager(globalConfigurationBuilder.build())) {
         RemoteCache<byte[], byte[]> remoteCache;
         if (!rebuildIndexOnly) {
            System.out.println("\n\n\n   ********************************  \n\n\n");
            System.out.println("Connecting remote client first.. if it doesn't connect we don't spend time loading data");
            remoteCache = remoteCacheManager.getCache(cacheName)
                  .withDataFormat(DataFormat.builder()
                        // Unknown ensures the client will pass the byte[] as is and the server will not try to
                        // encode it either
                        .keyType(MediaType.APPLICATION_UNKNOWN)
                        .valueType(MediaType.APPLICATION_UNKNOWN)
                        .build());
         } else {
            remoteCache = null;
         }

         long beginTime = System.nanoTime();

         System.out.println("\n\n\n   ********************************  \n\n\n");
         System.out.println("Starting cache from data directory.. this may take some time especially if store index was not shut down cleanly");

         ConfigurationBuilder config = new ConfigurationBuilder();
         Path dataPath = Path.of(dataDir.getAbsolutePath());
         if (dataDirName != null) {
            dataPath = dataPath.resolve(dataDirName);
         }
         Path indexPath = Path.of(dataDir.getAbsolutePath());
         if (indexDirName != null) {
            indexPath = indexPath.resolve(indexDirName);
         }
         // We are forcing octet stream so we don't need user classes in classpath
         config.encoding()
               .mediaType(MediaType.APPLICATION_PROTOSTREAM);
         config.clustering()
               .remoteTimeout(updateFrequency, TimeUnit.SECONDS);
         config.persistence()
               .addSoftIndexFileStore()
               .dataLocation(dataPath.toString())
               .indexLocation(indexPath.toString());

         Cache<byte[], byte[]> cache = cacheManager.createCache(cacheName, config.build())
               .getAdvancedCache().withMediaType(MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_OCTET_STREAM);

         System.out.println("\n\n\n   ********************************  \n\n\n");
         System.out.print("Cache started in " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beginTime) +
               " ms containing ");

         beginTime = System.nanoTime();

         int cacheSize = cache.size();

         System.out.print(cacheSize + " entries, size took: " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beginTime) + "ms");


         if (!rebuildIndexOnly) {
            performRemoteUpload(cache, remoteCache, cacheSize);
         }
      } catch (IOException e) {
         throw new RuntimeException(e);
      }
   }

   private void performRemoteUpload(Cache<byte[], byte[]> cache, RemoteCache<byte[], byte[]> remoteCache, int cacheSize) {
      System.out.println("\n\n\n   ********************************  \n\n\n");
      System.out.println("Starting remote upload using " + parallelInserts + " parallel inserts.. this will take some time (Updates every " + updateFrequency + " seconds)");

      long beginTime = System.nanoTime();
      // In case if there was an exception we have to use using to close the iterator so the cache can be stopped
      long total = Flowable.using(() -> cache.getAdvancedCache().cacheEntrySet().stream(), stream ->
                        Flowable.fromStream(stream)
                              .parallel(parallelInserts)
                              .concatMap(ce -> Flowable.fromCompletionStage(
                                    remoteCache.putIfAbsentAsync(ce.getKey(), ce.getValue(), ce.getLifespan(), TimeUnit.MILLISECONDS)
                                          .thenApply(Objects::nonNull)))
                              .sequential()
                  , AutoCloseable::close)
            // This will send a message every step as 5% of the updates are done
            .buffer(updateFrequency, TimeUnit.SECONDS).reduce(0, (count, l) -> {
               count += l.size();
               System.out.println("completed " + count + " (" + (float) 100 * count / cacheSize + "%)");
               return count;
            })
            .blockingGet();

      System.out.println("Loading complete... loaded " + total + " entries in " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beginTime) + " ms");
      System.out.println("\n\n\n   ********************************  \n\n\n");
   }
}
