package pojlib.install;

import android.app.Activity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.apache.commons.io.FileUtils;

import pojlib.util.*;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.*;

/**
 * This class reads data from a game version json and downloads its contents.
 * This works for the base game as well as modloaders
 */
public class Installer {

    private static final ExecutorService threadPool = Executors.newCachedThreadPool();

    /**
     * Will only download client if it is missing, however it will overwrite if sha1 does not match the downloaded client
     * 
     * @param minecraftVersionInfo The data from the version info json
     * @param gameDir Directory to download the client to
     * @return {@link CompletableFuture CompletableFuture&lt;String&gt;} completed with either the path of the newly downloaded jar or a RuntimeException if it fails after 5 retries.
     * @throws IOException See {@link DownloadUtils#downloadFile(String,File) downloadFile(String,File)}
     */
    public static CompletableFuture<String> installClient(VersionInfo minecraftVersionInfo, String gameDir) throws IOException {
        Logger.getInstance().appendToLog("Downloading Client");
        CompletableFuture<String> future = new CompletableFuture<>();
        threadPool.submit(() -> {
            File clientFile = new File(gameDir + "/versions/" + minecraftVersionInfo.id + "/" + minecraftVersionInfo.id + ".jar");
            for (int i = 0; i < 5; i++) {
                if (i == 4) {
                    future.completeExceptionally(new RuntimeException("Client download failed after 5 retries"));
                    return null;
                }

                if (!clientFile.exists())
                    DownloadUtils.downloadFile(minecraftVersionInfo.downloads.client.url, clientFile);
                if (DownloadUtils.compareSHA1(clientFile, minecraftVersionInfo.downloads.client.sha1)) {
                    future.complete(clientFile.getAbsolutePath());
                    break;
                }
            }
            return null;
        });
        return future;
    }

    /**
     * Will only download library if it is missing, however it will overwrite if sha1 does not match the downloaded library
     * @param versionInfo The data from the version info json
     * @param gameDir Directory to download libs to
     * @return {@link CompletableFuture CompletableFuture&lt;String&gt;} completed with either the classpath of the libs or a RuntimeException if it fails after 5 retries.
     * @throws IOException See {@link DownloadUtils#downloadFile(String,File) downloadFile(String,File)}
     */
    public static CompletableFuture<String> installLibraries(VersionInfo versionInfo, String gameDir) throws IOException {
        Logger.getInstance().appendToLog("Downloading Libraries for: " + versionInfo.id);

        CompletableFuture<String> future = new CompletableFuture<String>();
        threadPool.submit(() -> {
            StringJoiner classpath = new StringJoiner(File.pathSeparator);
            for (VersionInfo.Library library : versionInfo.libraries) {
                for (int i = 0; i < 5; i++) {
                    if (i == 4) {
                        future.completeExceptionally(new RuntimeException(String.format("Library download of %s failed after 5 retries", library.name)));
                        return null;
                    }

                    File libraryFile;
                    String sha1;

                    //Null means mod lib, otherwise vanilla lib
                    if (library.downloads == null) {
                        String path = parseLibraryNameToPath(library.name);
                        libraryFile = new File(gameDir + "/libraries/", path);
                        sha1 = APIHandler.getRaw(library.url + path + ".sha1");
                        if (!libraryFile.exists()) {
                            Logger.getInstance().appendToLog("Downloading: " + library.name);
                            DownloadUtils.downloadFile(library.url + path, libraryFile);
                        }
                    } else {
                        VersionInfo.Library.Artifact artifact = library.downloads.artifact;
                        libraryFile = new File(gameDir + "/libraries/", artifact.path);
                        sha1 = artifact.sha1;
                        if (!libraryFile.exists() && !artifact.path.contains("lwjgl")) {
                            Logger.getInstance().appendToLog("Downloading: " + library.name);
                            DownloadUtils.downloadFile(artifact.url, libraryFile);
                        }
                    }

                    if (DownloadUtils.compareSHA1(libraryFile, sha1)) {
                        // Add our GLFW
                        classpath.add(Constants.USER_HOME + "/lwjgl3/lwjgl-glfw-classes.jar");

                        classpath.add(libraryFile.getAbsolutePath());
                        break;
                    }
                }
            }
            future.complete(classpath.toString());
            return null;
        });
        return future;
    }

    //Only works on minecraft, not fabric, quilt, etc...
    //Will only download asset if it is missing

    /**
     * Only work on minecraft, not on any modloaders
     * Will only download assets if missing
     * @param minecraftVersionInfo The data from the version info json
     * @param gameDir Directory to download to
     * @return {@link CompletableFuture CompletableFuture&lt;String&gt;} completed with the directory of the assets, or an IOException if something fails
     */
    public static CompletableFuture<String> installAssets(Activity context, VersionInfo minecraftVersionInfo, String gameDir) {
        Logger.getInstance().appendToLog("Downloading assets");
        CompletableFuture<String> future = new CompletableFuture<>();
        threadPool.submit(() -> {
            JsonObject assets = APIHandler.getFullUrl(minecraftVersionInfo.assetIndex.url, JsonObject.class);

            // what is this monstrosity
            ThreadPoolExecutor tp = new ThreadPoolExecutor(5, 5, 100, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());

            for (Map.Entry<String, JsonElement> entry : assets.getAsJsonObject("objects").entrySet()) {
                DownloadTask thread = new DownloadTask(entry, minecraftVersionInfo, gameDir);
                tp.execute(thread);
            }

            tp.shutdown();
            try {
                while (!tp.awaitTermination(100, TimeUnit.MILLISECONDS));
            } catch (InterruptedException ignored) {}
            try {
                DownloadUtils.downloadFile(minecraftVersionInfo.assetIndex.url, new File(gameDir + "/assets/indexes/" + minecraftVersionInfo.assets + ".json"));

                FileUtils.writeByteArrayToFile(new File(Constants.MC_DIR + "/config/sodium-extra.properties"), FileUtil.loadFromAssetToByte(context, "sodium-extra.properties"));
                FileUtils.writeByteArrayToFile(new File(Constants.MC_DIR + "/config/sodium-mixins.properties"), FileUtil.loadFromAssetToByte(context, "sodium-mixins.properties"));
                FileUtils.writeByteArrayToFile(new File(Constants.MC_DIR + "/config/sodium-options.json"), FileUtil.loadFromAssetToByte(context, "sodium-options.json"));
                FileUtils.writeByteArrayToFile(new File(Constants.MC_DIR + "/config/vivecraft-config.properties"), FileUtil.loadFromAssetToByte(context, "vivecraft-config.properties"));
                FileUtils.writeByteArrayToFile(new File(Constants.MC_DIR + "/config/tweakeroo.json"), FileUtil.loadFromAssetToByte(context, "tweakeroo.json"));
                FileUtils.writeByteArrayToFile(new File(Constants.MC_DIR + "/config/smoothboot.json"), FileUtil.loadFromAssetToByte(context, "smoothboot.json"));
                FileUtils.writeByteArrayToFile(new File(Constants.MC_DIR + "/config/malilib.json"), FileUtil.loadFromAssetToByte(context, "malilib.json"));
                FileUtils.writeByteArrayToFile(new File(Constants.MC_DIR + "/config/immediatelyfast.json"), FileUtil.loadFromAssetToByte(context, "immediatelyfast.json"));
                FileUtils.writeByteArrayToFile(new File(Constants.MC_DIR + "/config/c2me.toml"), FileUtil.loadFromAssetToByte(context, "c2me.toml"));
                FileUtils.writeByteArrayToFile(new File(Constants.MC_DIR + "/config/moreculling.toml"), FileUtil.loadFromAssetToByte(context, "moreculling.toml"));
                FileUtils.writeByteArrayToFile(new File(Constants.MC_DIR + "/options.txt"), FileUtil.loadFromAssetToByte(context, "options.txt"));
                FileUtils.writeByteArrayToFile(new File(Constants.MC_DIR + "/servers.dat"), FileUtil.loadFromAssetToByte(context, "servers.dat"));
                FileUtils.writeByteArrayToFile(new File(Constants.MC_DIR + "/optionsviveprofiles.txt"), FileUtil.loadFromAssetToByte(context, "optionsviveprofiles.txt"));
            } catch (IOException e) {
                future.completeExceptionally(e);
            }
            if (!future.isCompletedExceptionally())
                future.complete(new File(gameDir + "/assets").getAbsolutePath());
            return null;
        });
        return future;
    }

    /**
     * A task used to download assets from json
     */
    public static class DownloadTask implements Runnable {
        Map.Entry<String, JsonElement> entry;
        VersionInfo versionInfo;
        String gameDir;

        public void run() {
            VersionInfo.Asset asset = new Gson().fromJson(entry.getValue(), VersionInfo.Asset.class);
            String path = asset.hash.substring(0, 2) + "/" + asset.hash;
            File assetFile = new File(gameDir + "/assets/objects/", path);

            if (!assetFile.exists()) {
                    Logger.getInstance().appendToLog("Downloading: " + entry.getKey());
                try {
                    DownloadUtils.downloadFile(Constants.MOJANG_RESOURCES_URL + "/" + path, assetFile);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        public DownloadTask(Map.Entry<String, JsonElement> entry, VersionInfo versionInfo, String gameDir) {
            this.entry = entry;
            this.versionInfo = versionInfo;
            this.gameDir = gameDir;
        }
    }

    /**
     *
     * @param activity A context variable
     * @return The path of the LWJGL jar or a failed future
     */
    public static CompletableFuture<String> installLwjgl(Activity activity) {
        CompletableFuture<String> future = new CompletableFuture<>();
        threadPool.submit(() -> {
            File lwjgl = new File(Constants.USER_HOME + "/lwjgl3/lwjgl-glfw-classes-3.2.3.jar");
            if (!lwjgl.exists()) {
                lwjgl.getParentFile().mkdirs();
                try {
                    FileUtil.write(lwjgl.getAbsolutePath(), FileUtil.loadFromAssetToByte(activity, "lwjgl/lwjgl-glfw-classes-3.2.3.jar"));
                } catch (IOException e) {
                    future.completeExceptionally(new RuntimeException(e));
                }
            }
            future.complete(lwjgl.getAbsolutePath());
            return null;
        });
        return future;
    }

    //Used for mod libraries, vanilla is handled a different (tbh better) way

    /**
     * Used for mod libraries, vanilla is handled in a different way
     * @param libraryName The name of the lib
     * @return The parsed path (as string)
     */
    private static String parseLibraryNameToPath(String libraryName) {
        String[] parts = libraryName.split(":");
        String location = parts[0].replace(".", "/");
        String name = parts[1];
        String version = parts[2];

        return String.format("%s/%s/%s/%s", location, name, version, name + "-" + version + ".jar");
    }
}
