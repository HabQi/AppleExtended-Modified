/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

/*
 * AppleExtended
 *
 * Asynchronous texture decoding for RTM vehicles. OpenGL uploads remain on the
 * render thread because Minecraft's OpenGL context is not thread safe.
 */
package jp.ngt.rtm.render;

import com.anatawa12.fixRtm.UtilsKt;
import com.anatawa12.fixRtm.asm.config.MainConfig;
import jp.ngt.ngtlib.io.NGTLog;
import jp.ngt.ngtlib.renderer.RenderDiagnostics;
import jp.ngt.ngtlib.renderer.model.TextureSet;
import jp.ngt.rtm.modelpack.modelset.ModelSetTrain;
import jp.ngt.rtm.modelpack.modelset.ModelSetVehicleBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.data.TextureMetadataSection;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Prevents first-use PNG/WebP decoding from blocking a vehicle render call.
 * Decode work is serialized in the common worker pool to limit peak memory;
 * at most one decoded texture is uploaded at the start of each render frame.
 */
@SideOnly(Side.CLIENT)
public final class VehicleTexturePreloader {
    public static final VehicleTexturePreloader INSTANCE = new VehicleTexturePreloader();

    private final Map<ResourceLocation, State> states = new HashMap<>();
    private final Map<ModelSetVehicleBase<?>, Set<ResourceLocation>> modelTextures = new IdentityHashMap<>();
    private final Set<ModelSetVehicleBase<?>> readyModels =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Queue<DecodeRequest> decodeQueue = new ArrayDeque<>();
    private final Queue<DecodedTexture> uploadQueue = new ArrayDeque<>();
    private boolean decodeRunning;
    private int generation;

    private VehicleTexturePreloader() {
    }

    /**
     * Queues every standard texture used by this vehicle and returns whether
     * rendering can proceed without triggering a synchronous texture load.
     */
    public boolean ensureReady(ModelSetVehicleBase<?> modelSet) {
        if (!MainConfig.asyncVehicleTextureLoading || modelSet == null || modelSet.isDummy()) {
            return true;
        }

        Set<ResourceLocation> textures;
        synchronized (this) {
            if (this.readyModels.contains(modelSet)) {
                return true;
            }
            textures = this.modelTextures.get(modelSet);
            if (textures == null) {
                textures = new LinkedHashSet<>();
                this.collectModelTextures(modelSet.modelObj, textures);
                if (modelSet.rollsignTexture != null) {
                    textures.add(modelSet.rollsignTexture);
                }
                if (modelSet instanceof ModelSetTrain) {
                    ModelSetTrain train = (ModelSetTrain) modelSet;
                    if (train.bogieModels != null) {
                        for (ModelObject bogie : train.bogieModels) {
                            this.collectModelTextures(bogie, textures);
                        }
                    }
                }
                textures.remove(null);
                textures = Collections.unmodifiableSet(textures);
                this.modelTextures.put(modelSet, textures);
            }
        }

        if (textures.isEmpty()) {
            synchronized (this) {
                this.readyModels.add(modelSet);
            }
            return true;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        TextureManager textureManager = minecraft.getTextureManager();
        TextureMode textureMode = OptiFineTextureCompat.getTextureMode();
        boolean ready = true;
        synchronized (this) {
            for (ResourceLocation texture : textures) {
                if (texture == null || textureManager.getTexture(texture) != null) {
                    this.states.put(texture, State.READY);
                    continue;
                }

                State state = this.states.get(texture);
                if (state == State.FAILED) {
                    // Let TextureManager use its normal missing-texture path.
                    continue;
                }
                ready = false;
                if (state == null) {
                    this.states.put(texture, State.QUEUED);
                    this.decodeQueue.offer(new DecodeRequest(texture, this.generation, textureMode));
                }
            }
            this.scheduleDecodeLocked();
            if (ready) {
                this.readyModels.add(modelSet);
            }
        }
        return ready;
    }

    private void collectModelTextures(ModelObject modelObject, Set<ResourceLocation> target) {
        if (modelObject == null || modelObject.textures == null) {
            return;
        }
        for (TextureSet set : modelObject.textures) {
            if (set == null) {
                continue;
            }
            if (set.material != null && set.material.texture != null) {
                target.add(set.material.texture);
            }
            if (set.subTextures != null) {
                Collections.addAll(target, set.subTextures);
            }
        }
    }

    /** Called from RenderTickEvent.START. */
    public void processFrame() {
        if (!MainConfig.asyncVehicleTextureLoading) {
            return;
        }
        DecodedTexture decoded;
        synchronized (this) {
            decoded = this.uploadQueue.poll();
        }
        if (decoded == null) {
            return;
        }

        if (decoded.generation != this.generation) {
            synchronized (this) {
                this.scheduleDecodeLocked();
            }
            return;
        }

        TextureMode currentMode = OptiFineTextureCompat.getTextureMode();
        if (decoded.textureMode != currentMode) {
            synchronized (this) {
                if (decoded.generation == this.generation) {
                    this.states.put(decoded.location, State.QUEUED);
                    this.decodeQueue.offer(new DecodeRequest(decoded.location, this.generation, currentMode));
                }
                this.scheduleDecodeLocked();
            }
            return;
        }

        TextureManager textureManager = Minecraft.getMinecraft().getTextureManager();
        if (textureManager.getTexture(decoded.location) != null) {
            synchronized (this) {
                this.states.put(decoded.location, State.READY);
                this.scheduleDecodeLocked();
            }
            return;
        }

        long start = RenderDiagnostics.ENABLED ? System.nanoTime() : 0L;
        SimpleTexture texture;
        if (decoded.syncFallback) {
            OptiFineTextureCompat.logSynchronousFallback(decoded.location, decoded.failure);
            texture = new SimpleTexture(decoded.location);
        } else {
            texture = new PreloadedTexture(decoded);
        }
        boolean loaded = textureManager.loadTexture(decoded.location, texture);
        long elapsed = RenderDiagnostics.ENABLED ? System.nanoTime() - start : 0L;
        synchronized (this) {
            this.states.put(decoded.location, loaded ? State.READY : State.FAILED);
            this.scheduleDecodeLocked();
        }
        if (RenderDiagnostics.ENABLED && elapsed >= 50_000_000L) {
            NGTLog.debug("[VehicleTexturePreloader] Slow upload %s: %.1f ms (%dx%d, %s)",
                    decoded.location, elapsed / 1_000_000.0D, decoded.width, decoded.height,
                    decoded.syncFallback ? "sync-fallback" : decoded.textureMode.name().toLowerCase());
        }
    }

    public synchronized void clear() {
        ++this.generation;
        this.states.clear();
        this.modelTextures.clear();
        this.readyModels.clear();
        this.decodeQueue.clear();
        this.uploadQueue.clear();
        // The running worker observes its old generation and discards the result.
    }

    private void scheduleDecodeLocked() {
        if (this.decodeRunning) {
            return;
        }
        DecodeRequest request = this.decodeQueue.poll();
        if (request == null) {
            return;
        }
        this.decodeRunning = true;
        this.states.put(request.location, State.DECODING);
        UtilsKt.getFixRTMCommonExecutor().submit(() -> this.decode(request));
    }

    private void decode(DecodeRequest request) {
        DecodedTexture result = null;
        Throwable failure = null;
        long start = RenderDiagnostics.ENABLED ? System.nanoTime() : 0L;
        IResourceManager resourceManager = Minecraft.getMinecraft().getResourceManager();
        if (request.textureMode == TextureMode.SYNC_FALLBACK) {
            result = DecodedTexture.syncFallback(request, OptiFineTextureCompat.getFailure());
        } else try (IResource resource = resourceManager.getResource(request.location)) {
            BufferedImage image = TextureUtil.readBufferedImage(resource.getInputStream());
            if (image == null) {
                throw new IOException("Unsupported or invalid image: " + request.location);
            }
            boolean blur = false;
            boolean clamp = false;
            if (resource.hasMetadata()) {
                try {
                    TextureMetadataSection metadata = resource.getMetadata("texture");
                    if (metadata != null) {
                        blur = metadata.getTextureBlur();
                        clamp = metadata.getTextureClamp();
                    }
                } catch (RuntimeException exception) {
                    NGTLog.debug("[VehicleTexturePreloader] Invalid texture metadata %s: %s",
                            request.location, exception.toString());
                }
            }

            if (request.textureMode == TextureMode.PBR) {
                try {
                    int width = image.getWidth();
                    int height = image.getHeight();
                    int pixelCount = Math.multiplyExact(width, height);
                    int[] pixels = new int[Math.multiplyExact(pixelCount, 3)];
                    image.getRGB(0, 0, width, height, pixels, 0, width);
                    Arrays.fill(pixels, pixelCount, pixelCount * 2, OptiFineTextureCompat.DEFAULT_NORMAL_COLOR);
                    OptiFineTextureCompat.loadNormalAndSpecular(
                            resourceManager, request.location, width, height, pixels);
                    result = DecodedTexture.pbr(request, pixels, width, height, blur, clamp);
                } catch (Throwable throwable) {
                    OptiFineTextureCompat.markPbrBroken(throwable);
                    result = DecodedTexture.syncFallback(request, throwable);
                }
            } else {
                result = DecodedTexture.base(request, image, blur, clamp);
            }
        } catch (Throwable throwable) {
            failure = throwable;
        }

        long elapsed = RenderDiagnostics.ENABLED ? System.nanoTime() - start : 0L;
        synchronized (this) {
            this.decodeRunning = false;
            if (request.generation == this.generation) {
                if (result != null) {
                    this.states.put(request.location, State.DECODED);
                    this.uploadQueue.offer(result);
                } else {
                    this.states.put(request.location, State.FAILED);
                    this.scheduleDecodeLocked();
                }
            } else {
                this.scheduleDecodeLocked();
            }
        }

        if (failure != null) {
            NGTLog.debug("[VehicleTexturePreloader] Decode failed %s: %s",
                    request.location, failure.toString());
        } else if (RenderDiagnostics.ENABLED && elapsed >= 50_000_000L) {
            NGTLog.debug("[VehicleTexturePreloader] Slow decode %s: %.1f ms",
                    request.location, elapsed / 1_000_000.0D);
        }
    }

    private enum State {
        QUEUED,
        DECODING,
        DECODED,
        READY,
        FAILED
    }

    private static final class DecodeRequest {
        private final ResourceLocation location;
        private final int generation;
        private final TextureMode textureMode;

        private DecodeRequest(ResourceLocation location, int generation, TextureMode textureMode) {
            this.location = location;
            this.generation = generation;
            this.textureMode = textureMode;
        }
    }

    private static final class DecodedTexture {
        private final ResourceLocation location;
        private final BufferedImage image;
        private final int[] pbrPixels;
        private final int width;
        private final int height;
        private final boolean blur;
        private final boolean clamp;
        private final int generation;
        private final TextureMode textureMode;
        private final boolean syncFallback;
        private final Throwable failure;

        private DecodedTexture(DecodeRequest request, BufferedImage image, int[] pbrPixels,
                               int width, int height, boolean blur, boolean clamp,
                               boolean syncFallback, Throwable failure) {
            this.location = request.location;
            this.image = image;
            this.pbrPixels = pbrPixels;
            this.width = width;
            this.height = height;
            this.blur = blur;
            this.clamp = clamp;
            this.generation = request.generation;
            this.textureMode = request.textureMode;
            this.syncFallback = syncFallback;
            this.failure = failure;
        }

        private static DecodedTexture base(DecodeRequest request, BufferedImage image,
                                           boolean blur, boolean clamp) {
            return new DecodedTexture(request, image, null, image.getWidth(), image.getHeight(),
                    blur, clamp, false, null);
        }

        private static DecodedTexture pbr(DecodeRequest request, int[] pixels, int width, int height,
                                          boolean blur, boolean clamp) {
            return new DecodedTexture(request, null, pixels, width, height,
                    blur, clamp, false, null);
        }

        private static DecodedTexture syncFallback(DecodeRequest request, Throwable failure) {
            return new DecodedTexture(request, null, null, 0, 0,
                    false, false, true, failure);
        }
    }

    /**
     * TextureManager invokes loadTexture while registering this object. The
     * already-decoded image is consumed once; resource reloads use vanilla's
     * synchronous loading path while the loading screen is active.
     */
    private static final class PreloadedTexture extends SimpleTexture {
        private final ResourceLocation location;
        private BufferedImage image;
        private int[] pbrPixels;
        private final int width;
        private final int height;
        private final boolean blur;
        private final boolean clamp;

        private PreloadedTexture(DecodedTexture decoded) {
            super(decoded.location);
            this.location = decoded.location;
            this.image = decoded.image;
            this.pbrPixels = decoded.pbrPixels;
            this.width = decoded.width;
            this.height = decoded.height;
            this.blur = decoded.blur;
            this.clamp = decoded.clamp;
        }

        @Override
        public void loadTexture(IResourceManager resourceManager) throws IOException {
            BufferedImage decoded = this.image;
            int[] decodedPbr = this.pbrPixels;
            if (decoded == null && decodedPbr == null) {
                super.loadTexture(resourceManager);
                return;
            }
            this.image = null;
            this.pbrPixels = null;
            this.deleteGlTexture();
            if (decodedPbr != null) {
                try {
                    OptiFineTextureCompat.uploadPbr(
                            this, decodedPbr, this.width, this.height, this.blur, this.clamp);
                } catch (Throwable throwable) {
                    OptiFineTextureCompat.markPbrBroken(throwable);
                    OptiFineTextureCompat.logSynchronousFallback(this.location, throwable);
                    super.loadTexture(resourceManager);
                }
            } else {
                TextureUtil.uploadTextureImageAllocate(this.getGlTextureId(), decoded, this.blur, this.clamp);
            }
        }
    }

    private enum TextureMode {
        BASE_ONLY,
        PBR,
        SYNC_FALLBACK
    }

    /** Optional OptiFine bridge. All OpenGL-bearing methods are called only from processFrame(). */
    private static final class OptiFineTextureCompat {
        private static final int DEFAULT_NORMAL_COLOR = 0xFF7F7FFF;

        private static final boolean OPTIFINE_PRESENT;
        private static final Method IS_SHADERS;
        private static final Method LOAD_NS_MAP;
        private static final Method GET_MULTI_TEX_ID;
        private static final Method SETUP_TEXTURE;
        private static final Throwable INITIALIZATION_FAILURE;

        private static volatile boolean shaderQueryBroken;
        private static volatile boolean pbrBroken;
        private static volatile Throwable runtimeFailure;
        private static boolean fallbackLogged;

        static {
            boolean optiFinePresent = false;
            Method isShaders = null;
            Method loadNsMap = null;
            Method getMultiTexId = null;
            Method setupTexture = null;
            Throwable initializationFailure = null;
            try {
                Class<?> configClass = Class.forName("Config");
                optiFinePresent = true;
                isShaders = configClass.getMethod("isShaders");

                Class<?> shadersTexClass = Class.forName("net.optifine.shaders.ShadersTex");
                Class<?> multiTexIdClass = Class.forName("net.optifine.shaders.MultiTexID");
                loadNsMap = shadersTexClass.getMethod("loadNSMap", IResourceManager.class,
                        ResourceLocation.class, int.class, int.class, int[].class);
                getMultiTexId = shadersTexClass.getMethod("getMultiTexID", AbstractTexture.class);
                setupTexture = shadersTexClass.getMethod("setupTexture", multiTexIdClass, int[].class,
                        int.class, int.class, boolean.class, boolean.class);
            } catch (ClassNotFoundException exception) {
                if (optiFinePresent) {
                    initializationFailure = exception;
                }
            } catch (Throwable throwable) {
                initializationFailure = unwrap(throwable);
            }
            OPTIFINE_PRESENT = optiFinePresent;
            IS_SHADERS = isShaders;
            LOAD_NS_MAP = loadNsMap;
            GET_MULTI_TEX_ID = getMultiTexId;
            SETUP_TEXTURE = setupTexture;
            INITIALIZATION_FAILURE = initializationFailure;
        }

        private static TextureMode getTextureMode() {
            if (!OPTIFINE_PRESENT) {
                return TextureMode.BASE_ONLY;
            }
            if (shaderQueryBroken || IS_SHADERS == null) {
                return TextureMode.SYNC_FALLBACK;
            }

            final boolean shadersEnabled;
            try {
                shadersEnabled = Boolean.TRUE.equals(IS_SHADERS.invoke(null));
            } catch (Throwable throwable) {
                shaderQueryBroken = true;
                runtimeFailure = unwrap(throwable);
                return TextureMode.SYNC_FALLBACK;
            }
            if (!shadersEnabled) {
                return TextureMode.BASE_ONLY;
            }
            if (pbrBroken || LOAD_NS_MAP == null || GET_MULTI_TEX_ID == null || SETUP_TEXTURE == null) {
                return TextureMode.SYNC_FALLBACK;
            }
            return TextureMode.PBR;
        }

        private static void loadNormalAndSpecular(IResourceManager resourceManager, ResourceLocation location,
                                                  int width, int height, int[] pixels) throws Throwable {
            try {
                LOAD_NS_MAP.invoke(null, resourceManager, location, width, height, pixels);
            } catch (Throwable throwable) {
                throw unwrap(throwable);
            }
        }

        private static void uploadPbr(AbstractTexture texture, int[] pixels, int width, int height,
                                      boolean blur, boolean clamp) throws Throwable {
            try {
                Object multiTexId = GET_MULTI_TEX_ID.invoke(null, texture);
                SETUP_TEXTURE.invoke(null, multiTexId, pixels, width, height, blur, clamp);
            } catch (Throwable throwable) {
                throw unwrap(throwable);
            }
        }

        private static void markPbrBroken(Throwable throwable) {
            pbrBroken = true;
            runtimeFailure = unwrap(throwable);
        }

        private static Throwable getFailure() {
            Throwable failure = runtimeFailure;
            return failure != null ? failure : INITIALIZATION_FAILURE;
        }

        private static synchronized void logSynchronousFallback(ResourceLocation location, Throwable throwable) {
            if (fallbackLogged) {
                return;
            }
            fallbackLogged = true;
            Throwable failure = throwable != null ? unwrap(throwable) : getFailure();
            NGTLog.debug("[VehicleTexturePreloader] OptiFine async PBR unavailable for %s; "
                            + "using synchronous SimpleTexture loading: %s",
                    location, failure == null ? "unknown compatibility failure" : failure.toString());
        }

        private static Throwable unwrap(Throwable throwable) {
            Throwable current = throwable;
            while (current instanceof InvocationTargetException
                    && ((InvocationTargetException) current).getTargetException() != null) {
                current = ((InvocationTargetException) current).getTargetException();
            }
            return current;
        }
    }
}
