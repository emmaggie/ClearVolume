package clearvolume.renderer.cleargl;

import static java.lang.Math.max;
import static java.lang.Math.min;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.SystemUtils;

import cleargl.ClearGLEventListener;
import cleargl.ClearGLWindow;
import cleargl.GLAttribute;
import cleargl.GLError;
import cleargl.GLFloatArray;
import cleargl.GLMatrix;
import cleargl.GLPixelBufferObject;
import cleargl.GLProgram;
import cleargl.GLTexture;
import cleargl.GLUniform;
import cleargl.GLVertexArray;
import cleargl.GLVertexAttributeArray;
import cleargl.util.recorder.GLVideoRecorder;
import clearvolume.controller.RotationControllerInterface;
import clearvolume.controller.RotationControllerWithRenderNotification;
import clearvolume.renderer.ClearVolumeRendererBase;
import clearvolume.renderer.cleargl.overlay.Overlay;
import clearvolume.renderer.cleargl.overlay.Overlay2D;
import clearvolume.renderer.cleargl.overlay.Overlay3D;
import clearvolume.renderer.cleargl.overlay.o3d.BoxOverlay;
import clearvolume.renderer.cleargl.utils.ScreenToEyeRay;
import clearvolume.renderer.cleargl.utils.ScreenToEyeRay.EyeRay;
import clearvolume.renderer.listeners.EyeRayListener;

import com.jogamp.nativewindow.WindowClosingProtocol.WindowClosingMode;
import com.jogamp.newt.awt.NewtCanvasAWT;
import com.jogamp.newt.event.MouseEvent;
import com.jogamp.newt.event.WindowAdapter;
import com.jogamp.newt.event.WindowEvent;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.math.Quaternion;

import coremem.types.NativeTypeEnum;

/**
 * Abstract Class JoglPBOVolumeRenderer
 *
 * Classes that derive from this abstract class are provided with basic
 * JOGL-based display capability for implementing a ClearVolumeRenderer.
 *
 * @author Loic Royer 2014
 *
 */
public abstract class ClearGLVolumeRenderer extends

ClearVolumeRendererBase implements ClearGLEventListener
{

	private static final double cTextureDimensionChangeRatioThreshold = 1.05;
	private static final double cTextureAspectChangeRatioThreshold = 1.05;
	private static final long cMaxWaitingTimeForAcquiringDisplayLockInMs = 200;

	private static final GLMatrix cOverlay2dProjectionMatrix = GLMatrix.getOrthoProjectionMatrix(	-1,
																																																1,
																																																-1,
																																																1,
																																																0,
																																																1000);/**/;

	static
	{
		// attempt at solving Jug's Dreadlock bug:
		final GLProfile lProfile = GLProfile.get(GLProfile.GL3);
		// System.out.println( lProfile );
	}

	// ClearGL Window.
	private volatile ClearGLWindow mClearGLWindow;

	private NewtCanvasAWT mNewtCanvasAWT;
	private volatile int mLastWindowWidth, mLastWindowHeight;
	private volatile int mViewportX, mViewportY, mViewportWidth,
			mViewportHeight;

	// pixelbuffer objects.
	protected GLPixelBufferObject[] mPixelBufferObjects;

	// texture and its dimensions.
	private final GLTexture[] mLayerTextures;

	// Internal fields for calculating FPS.
	private volatile int step = 0;
	private volatile long prevTimeNS = -1;

	// Overlay3D stuff:
	private final Map<String, Overlay> mOverlayMap = new ConcurrentHashMap<String, Overlay>();

	// Window:
	private final String mWindowName;
	private GLProgram mGLProgram;

	// Shader attributes, uniforms and arrays:
	private GLAttribute mPositionAttribute;
	private GLVertexArray mQuadVertexArray;
	private GLVertexAttributeArray mPositionAttributeArray;
	private GLUniform mQuadProjectionMatrixUniform;
	private GLAttribute mTexCoordAttribute;
	private GLUniform[] mTexUnits;
	private GLVertexAttributeArray mTexCoordAttributeArray;

	private final GLMatrix mBoxModelViewMatrix = new GLMatrix();
	private final GLMatrix mVolumeViewMatrix = new GLMatrix();
	private final GLMatrix mQuadProjectionMatrix = new GLMatrix();

	// textures width and height:
	private volatile int mMaxRenderWidth, mMaxRenderHeight,
			mRenderWidth, mRenderHeight;
	private volatile boolean mUpdateTextureWidthHeight = true;

	private volatile boolean mRequestDisplay = true;

	// Recorder:
	private final GLVideoRecorder mGLVideoRecorder = new GLVideoRecorder(new File(SystemUtils.USER_HOME,
																																								"Videos/ClearVolume"));

	private final float mLightVector[] = new float[]
	{ -1.f, 1.f, 1.f };

	/**
	 * Constructs an instance of the JoglPBOVolumeRenderer class given a window
	 * name and its dimensions.
	 *
	 * @param pWindowName
	 *          window name
	 * @param pWindowWidth
	 *          window width
	 * @param pWindowHeight
	 *          window height
	 */
	public ClearGLVolumeRenderer(	final String pWindowName,
																final int pWindowWidth,
																final int pWindowHeight)
	{
		this(	pWindowName,
					pWindowWidth,
					pWindowHeight,
					NativeTypeEnum.UnsignedByte);
	}

	/**
	 * Constructs an instance of the JoglPBOVolumeRenderer class given a window
	 * name, its dimensions, and bytes-per-voxel.
	 *
	 * @param pWindowName
	 *          window name
	 * @param pWindowWidth
	 *          window width
	 * @param pWindowHeight
	 *          window height
	 * @param pNativeTypeEnum
	 *          native type
	 */
	public ClearGLVolumeRenderer(	final String pWindowName,
																final int pWindowWidth,
																final int pWindowHeight,
																final NativeTypeEnum pNativeTypeEnum)
	{
		this(	pWindowName,
					pWindowWidth,
					pWindowHeight,
					pNativeTypeEnum,
					768,
					768);
	}

	/**
	 * Constructs an instance of the JoglPBOVolumeRenderer class given a window
	 * name, its dimensions, and bytes-per-voxel.
	 *
	 * @param pWindowName
	 *          window name
	 * @param pWindowWidth
	 *          window width
	 * @param pWindowHeight
	 *          window height
	 * @param pNativeTypeEnum
	 *          native type
	 * @param pMaxRenderWidth
	 *          max render width
	 * @param pMaxRenderHeight
	 *          max render height
	 */
	public ClearGLVolumeRenderer(	final String pWindowName,
																final int pWindowWidth,
																final int pWindowHeight,
																final NativeTypeEnum pNativeTypeEnum,
																final int pMaxRenderWidth,
																final int pMaxRenderHeight)
	{
		this(	pWindowName,
					pWindowWidth,
					pWindowHeight,
					pNativeTypeEnum,
					pMaxRenderWidth,
					pMaxRenderHeight,
					1);
	}

	/**
	 * Constructs an instance of the JoglPBOVolumeRenderer class given a window
	 * name, its dimensions, and bytes-per-voxel.
	 *
	 * @param pWindowName
	 *          window name
	 * @param pWindowWidth
	 *          window width
	 * @param pWindowHeight
	 *          window height
	 * @param pNativeTypeEnum
	 *          native type
	 * @param pMaxRenderWidth
	 *          max render width
	 * @param pMaxRenderHeight
	 *          max render height
	 * @param pUseInCanvas
	 *          if true, this Renderer will not be displayed in a window of it's
	 *          own, but must be embedded in a GUI as Canvas.
	 */
	public ClearGLVolumeRenderer(	final String pWindowName,
																final int pWindowWidth,
																final int pWindowHeight,
																final NativeTypeEnum pNativeTypeEnum,
																final int pMaxRenderWidth,
																final int pMaxRenderHeight,
																final boolean pUseInCanvas)
	{
		this(	pWindowName,
					pWindowWidth,
					pWindowHeight,
					pNativeTypeEnum,
					pMaxRenderWidth,
					pMaxRenderHeight,
					1,
					pUseInCanvas);
	}

	/**
	 * Constructs an instance of the JoglPBOVolumeRenderer class given a window
	 * name, its dimensions, number of bytes-per-voxel, max texture width, height
	 * and number of render layers.
	 *
	 * @param pWindowName
	 *          window name
	 * @param pWindowWidth
	 *          window width
	 * @param pWindowHeight
	 *          window height
	 * @param pNativeTypeEnum
	 *          native type
	 * @param pMaxTextureWidth
	 *          max render width
	 * @param pMaxTextureHeight
	 *          max render height
	 * @param pNumberOfRenderLayers
	 *          number of render layers
	 */
	public ClearGLVolumeRenderer(	final String pWindowName,
																final int pWindowWidth,
																final int pWindowHeight,
																final NativeTypeEnum pNativeTypeEnum,
																final int pMaxTextureWidth,
																final int pMaxTextureHeight,
																final int pNumberOfRenderLayers)
	{
		this(	pWindowName,
					pWindowWidth,
					pWindowHeight,
					pNativeTypeEnum,
					pMaxTextureWidth,
					pMaxTextureHeight,
					1,
					false);
	}

	/**
	 * Constructs an instance of the JoglPBOVolumeRenderer class given a window
	 * name, its dimensions, number of bytes-per-voxel, max texture width, height
	 * and number of render layers.
	 *
	 * @param pWindowName
	 *          window name
	 * @param pWindowWidth
	 *          window width
	 * @param pWindowHeight
	 *          window height
	 * @param pNativeTypeEnum
	 *          native type
	 * @param pMaxRenderWidth
	 *          max render width
	 * @param pMaxRenderHeight
	 *          max render height
	 * @param pNumberOfRenderLayers
	 *          number of render layers
	 * @param pUseInCanvas
	 *          if true, this Renderer will not be displayed in a window of it's
	 *          own, but must be embedded in a GUI as Canvas.
	 */
	@SuppressWarnings("unchecked")
	public ClearGLVolumeRenderer(	final String pWindowName,
																final int pWindowWidth,
																final int pWindowHeight,
																final NativeTypeEnum pNativeTypeEnum,
																final int pMaxRenderWidth,
																final int pMaxRenderHeight,
																final int pNumberOfRenderLayers,
																final boolean pUseInCanvas)
	{
		super(pNumberOfRenderLayers);

		mViewportWidth = pWindowWidth;
		mViewportHeight = pWindowHeight;

		mMaxRenderWidth = pMaxRenderWidth;
		mMaxRenderHeight = pMaxRenderHeight;

		mRenderWidth = min(pMaxRenderWidth, pWindowWidth);
		mRenderHeight = min(pMaxRenderHeight, pWindowHeight);

		mWindowName = pWindowName;
		mLastWindowWidth = pWindowWidth;
		mLastWindowHeight = pWindowHeight;
		setNumberOfRenderLayers(pNumberOfRenderLayers);

		mLayerTextures = new GLTexture[getNumberOfRenderLayers()];

		resetBrightnessAndGammaAndTransferFunctionRanges();
		resetRotationTranslation();
		setNativeType(pNativeTypeEnum);

		addOverlay(new BoxOverlay());

		mClearGLWindow = new ClearGLWindow(	pWindowName,
																				pWindowWidth,
																				pWindowHeight,
																				this);
		mClearGLWindow.setFPS(60);

		mClearGLWindow.start();

		if (pUseInCanvas)
		{
			mNewtCanvasAWT = mClearGLWindow.getNewtCanvasAWT();
			mNewtCanvasAWT.setShallUseOffscreenLayer(true);
		}
		else
		{
			mNewtCanvasAWT = null;
		}

		// Initialize the mouse controls
		final MouseControl lMouseControl = new MouseControl(this);
		mClearGLWindow.addMouseListener(lMouseControl);

		// Initialize the keyboard controls
		final KeyboardControl lKeyboardControl = new KeyboardControl(	this,
																																	lMouseControl);
		mClearGLWindow.addKeyListener(lKeyboardControl);

		mClearGLWindow.addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowDestroyNotify(final WindowEvent pE)
			{
				super.windowDestroyNotify(pE);
			};
		});

	}

	@Override
	public void setClearGLWindow(final ClearGLWindow pClearGLWindow)
	{

		mClearGLWindow = pClearGLWindow;
	}

	@Override
	public ClearGLWindow getClearGLWindow()
	{
		return mClearGLWindow;
	};

	@Override
	public void close()
	{

		if (mNewtCanvasAWT != null)
		{
			mNewtCanvasAWT = null;

			return;
		}

		try
		{
			mClearGLWindow.close();
		}
		catch (final NullPointerException e)
		{
		}
		catch (final Throwable e)
		{
			System.err.println(e.getLocalizedMessage());
		}

		super.close();

	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#isShowing()
	 */
	@Override
	public boolean isShowing()
	{
		try
		{
			if (mNewtCanvasAWT != null)
				return mNewtCanvasAWT.isVisible();

			if (mClearGLWindow != null)
				return mClearGLWindow.isVisible();
		}
		catch (final NullPointerException e)
		{
			return false;
		}

		return false;
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#setVisible(boolean)
	 */
	@Override
	public void setVisible(final boolean pIsVisible)
	{
		if (mNewtCanvasAWT == null)
			mClearGLWindow.setVisible(pIsVisible);
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#getWindowName()
	 */
	@Override
	public String getWindowName()
	{
		return mWindowName;
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#getWindowWidth()
	 */
	@Override
	public int getWindowWidth()
	{
		return mClearGLWindow.getWidth();
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#getWindowHeight()
	 */
	@Override
	public int getWindowHeight()
	{
		return mClearGLWindow.getHeight();
	}

	/**
	 * Returns the render texture width.
	 *
	 * @return texture width
	 */
	public int getRenderWidth()
	{
		return mRenderWidth;
	}

	/**
	 * Returns the render texture height.
	 *
	 * @return texture height
	 */
	public int getRenderHeight()
	{
		return mRenderHeight;
	}

	/**
	 * Implementation of GLEventListener: Called to initialize the GLAutoDrawable.
	 * This method will initialize the JCudaDriver and cause the initialization of
	 * CUDA and the OpenGL PBO.
	 */
	@Override
	public void init(final GLAutoDrawable drawable)
	{
		final GL lGL = drawable.getGL();
		lGL.setSwapInterval(1);

		lGL.glDisable(GL.GL_DEPTH_TEST);
		lGL.glEnable(GL.GL_BLEND);
		lGL.glDisable(GL.GL_STENCIL_TEST);

		lGL.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
		lGL.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);

		if (initVolumeRenderer())
		{

			// texture display: construct the program and related objects
			try
			{
				final InputStream lVertexShaderResourceAsStream = ClearGLVolumeRenderer.class.getResourceAsStream("shaders/tex_vert.glsl");
				final InputStream lFragmentShaderResourceAsStream = ClearGLVolumeRenderer.class.getResourceAsStream("shaders/tex_frag.glsl");

				final String lVertexShaderSource = IOUtils.toString(lVertexShaderResourceAsStream,
																														"UTF-8");
				String lFragmentShaderSource = IOUtils.toString(lFragmentShaderResourceAsStream,
																												"UTF-8");

				for (int i = 1; i < getNumberOfRenderLayers(); i++)
				{
					final String lStringToInsert1 = String.format("uniform sampler2D texUnit%d; \n//insertpoint1",
																												i);
					final String lStringToInsert2 = String.format("tempOutColor = max(tempOutColor,texture(texUnit%d, ftexcoord));\n//insertpoint2",
																												i);

					lFragmentShaderSource = lFragmentShaderSource.replace("//insertpoint1",
																																lStringToInsert1);
					lFragmentShaderSource = lFragmentShaderSource.replace("//insertpoint2",
																																lStringToInsert2);
				}
				// System.out.println(lFragmentShaderSource);

				mGLProgram = GLProgram.buildProgram(lGL,
																						lVertexShaderSource,
																						lFragmentShaderSource);
				mQuadProjectionMatrixUniform = mGLProgram.getUniform("projection");
				mPositionAttribute = mGLProgram.getAtribute("position");
				mTexCoordAttribute = mGLProgram.getAtribute("texcoord");
				mTexUnits = new GLUniform[getNumberOfRenderLayers()];
				for (int i = 0; i < getNumberOfRenderLayers(); i++)
				{
					mTexUnits[i] = mGLProgram.getUniform("texUnit" + i);
					mTexUnits[i].setInt(i);
				}

				mQuadVertexArray = new GLVertexArray(mGLProgram);
				mQuadVertexArray.bind();
				mPositionAttributeArray = new GLVertexAttributeArray(	mPositionAttribute,
																															4);

				final GLFloatArray lVerticesFloatArray = new GLFloatArray(6,

				4);

				lVerticesFloatArray.add(-1, -1, 0, 1);
				lVerticesFloatArray.add(1, -1, 0, 1);
				lVerticesFloatArray.add(1, 1, 0, 1);
				lVerticesFloatArray.add(-1, -1, 0, 1);
				lVerticesFloatArray.add(1, 1, 0, 1);
				lVerticesFloatArray.add(-1, 1, 0, 1);

				mQuadVertexArray.addVertexAttributeArray(	mPositionAttributeArray,
																									lVerticesFloatArray.getFloatBuffer());

				mTexCoordAttributeArray = new GLVertexAttributeArray(	mTexCoordAttribute,
																															2);

				final GLFloatArray lTexCoordFloatArray = new GLFloatArray(6,
																																	2);
				lTexCoordFloatArray.add(0, 0);
				lTexCoordFloatArray.add(1, 0);
				lTexCoordFloatArray.add(1, 1);
				lTexCoordFloatArray.add(0, 0);
				lTexCoordFloatArray.add(1, 1);
				lTexCoordFloatArray.add(0, 1);

				mQuadVertexArray.addVertexAttributeArray(	mTexCoordAttributeArray,
																									lTexCoordFloatArray.getFloatBuffer());

			}
			catch (final IOException e)
			{
				e.printStackTrace();
			}

			for (final Overlay lOverlay : mOverlayMap.values())
			{
				try
				{
					lOverlay.init(lGL, this);
				}
				catch (final Throwable e)
				{
					e.printStackTrace();
				}
			}

			ensureTextureAllocated();

		}

	}

	private void ensureTextureAllocated()
	{
		if (mUpdateTextureWidthHeight)
		{
			getDisplayLock().lock();
			try
			{
				for (int i = 0; i < getNumberOfRenderLayers(); i++)
				{
					if (mLayerTextures[i] != null)
						mLayerTextures[i].close();

					mLayerTextures[i] = new GLTexture(mGLProgram,
																						NativeTypeEnum.UnsignedByte,
																						4,
																						mRenderWidth,
																						mRenderHeight,
																						1,
																						true,
																						2);
					mLayerTextures[i].clear();

				}

				notifyChangeOfTextureDimensions();
				notifyChangeOfVolumeRenderingParameters();

			}
			finally
			{
				mUpdateTextureWidthHeight = false;
				if (getDisplayLock().isHeldByCurrentThread())
					getDisplayLock().unlock();
			}
		}
	}

	abstract protected void notifyChangeOfTextureDimensions();

	/**
	 * @return true if the implemented renderer initialized successfully.
	 */
	protected abstract boolean initVolumeRenderer();

	public void copyBufferToTexture(final int pRenderLayerIndex,
																	final ByteBuffer pByteBuffer)
	{
		pByteBuffer.rewind();
		mLayerTextures[pRenderLayerIndex].copyFrom(pByteBuffer);
		mLayerTextures[pRenderLayerIndex].updateMipMaps();
	}

	public void clearTexture(final int pRenderLayerIndex)
	{
		mLayerTextures[pRenderLayerIndex].clear();
	}

	/**
	 * Implementation of GLEventListener: Called when the given GLAutoDrawable is
	 * to be displayed.
	 */
	@Override
	public void display(final GLAutoDrawable pDrawable)
	{
		displayInternal(pDrawable);
	}

	private void displayInternal(final GLAutoDrawable pDrawable)
	{
		final boolean lTryLock = true;

		pDrawable.getGL();

		mDisplayReentrantLock.lock(); /*
																	* tryLock(
																	* cMaxWaitingTimeForAcquiringDisplayLockInMs
																	* , TimeUnit.MILLISECONDS);/*
																	*/

		if (lTryLock)
			try
			{
				ensureTextureAllocated();

				final boolean lOverlay2DChanged = isOverlay2DChanged();
				final boolean lOverlay3DChanged = isOverlay3DChanged();

				/*
				 * if (!isNewVolumeDataAvailable() && !lOverlay2DChanged &&
				 * !lOverlay3DChanged && !haveVolumeRenderingParametersChanged()
				 * && !getAdaptiveLODController().isRedrawNeeded() &&
				 * !pForceRedraw) { return; }/*
				 */

				/*
				 * System.out.println("isNewVolumeDataAvailable()=" +
				 * isNewVolumeDataAvailable());
				 * System.out.println("lOverlay2DChanged=" + lOverlay2DChanged);
				 * System.out.println("lOverlay3DChanged=" + lOverlay3DChanged);
				 * System.out.println("haveVolumeRenderingParametersChanged()="
				 * + haveVolumeRenderingParametersChanged());
				 * System.out.println(
				 * "getAdaptiveLODController().isRedrawNeeded()=" +
				 * getAdaptiveLODController().isRedrawNeeded());
				 * System.out.println("pForceRedraw=" + pForceRedraw);/*
				 */

				final GL lGL = pDrawable.getGL();
				lGL.glClearColor(0, 0, 0, 1);
				lGL.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
				lGL.glDisable(GL.GL_CULL_FACE);
				lGL.glEnable(GL.GL_BLEND);
				lGL.glBlendFunc(GL.GL_ONE, GL.GL_ONE);
				lGL.glBlendEquation(GL2ES3.GL_MAX);

				setDefaultProjectionMatrix();

				final GLMatrix lModelViewMatrix = getModelViewMatrix();
				final GLMatrix lProjectionMatrix = getDefaultProjectionMatrix();

				GLError.printGLErrors(lGL, "BEFORE RENDER VOLUME");

				if (haveVolumeRenderingParametersChanged() || isNewVolumeDataAvailable())
					getAdaptiveLODController().renderingParametersOrVolumeDataChanged();

				final boolean lLastRenderPass = getAdaptiveLODController().beforeRendering();

				renderVolume(	lModelViewMatrix.clone()
																			.invert()
																			.transpose()
																			.getFloatArray(),
											lProjectionMatrix.clone()
																				.invert()
																				.transpose()
																				.getFloatArray());

				getAdaptiveLODController().afterRendering();

				clearChangeOfVolumeParametersFlag();

				GLError.printGLErrors(lGL, "AFTER RENDER VOLUME");

				mGLProgram.use(lGL);

				for (int i = 0; i < getNumberOfRenderLayers(); i++)
					mLayerTextures[i].bind(i);

				mQuadProjectionMatrixUniform.setFloatMatrix(mQuadProjectionMatrix.getFloatArray(),
																										false);

				mQuadVertexArray.draw(GL.GL_TRIANGLES);

				final GLMatrix lAspectRatioCorrectedProjectionMatrix = getAspectRatioCorrectedProjectionMatrix();

				renderOverlays3D(	lGL,
													lAspectRatioCorrectedProjectionMatrix,
													lModelViewMatrix);

				renderOverlays2D(lGL, cOverlay2dProjectionMatrix);

				updateFrameRateDisplay();

				if (lLastRenderPass)
					mGLVideoRecorder.screenshot(pDrawable,
																			!getAutoRotateController().isRotating());

			}
			finally
			{
				if (mDisplayReentrantLock.isHeldByCurrentThread())
					mDisplayReentrantLock.unlock();
			}

	}

	private void setDefaultProjectionMatrix()
	{
		if (getClearGLWindow() != null)
			getClearGLWindow().setPerspectiveProjectionMatrix(getFOV(),
																												1,
																												.1f,
																												1000);
	}

	private GLMatrix getDefaultProjectionMatrix()
	{
		final GLMatrix lProjectionMatrix = new GLMatrix();
		lProjectionMatrix.setPerspectiveProjectionMatrix(	getFOV(),
																											1,
																											.1f,
																											1000);
		return lProjectionMatrix;
	}

	private GLMatrix getAspectRatioCorrectedProjectionMatrix()
	{
		final GLMatrix lProjectionMatrix = new GLMatrix();
		lProjectionMatrix.setPerspectiveProjectionMatrix(	getFOV(),
																											1,
																											.1f,
																											1000);
		lProjectionMatrix.mult(0, 0, mQuadProjectionMatrix.get(0, 0));
		lProjectionMatrix.mult(1, 1, mQuadProjectionMatrix.get(1, 1));/**/
		return lProjectionMatrix;
	}

	public float[] getLightVector()
	{
		return mLightVector;
	}

	public void setLightVector(final float[] pLight)
	{
		mLightVector[0] = pLight[0];
		mLightVector[1] = pLight[1];
		mLightVector[2] = pLight[2];
		notifyChangeOfVolumeRenderingParameters();
	}

	private GLMatrix getModelViewMatrix()
	{
		// scaling...

		final double lScaleX = getVolumeSizeX() * getVoxelSizeX();
		final double lScaleY = getVolumeSizeY() * getVoxelSizeY();
		final double lScaleZ = getVolumeSizeZ() * getVoxelSizeZ();

		final double lMaxScale = max(max(lScaleX, lScaleY), lScaleZ);

		// building up the inverse Modelview matrix

		applyControllersTransform();

		final GLMatrix lModelViewMatrix = new GLMatrix();
		lModelViewMatrix.setIdentity();

		lModelViewMatrix.translate(	getTranslationX(),
																getTranslationY(),
																getTranslationZ());/**/

		lModelViewMatrix.mult(getQuaternion());

		lModelViewMatrix.scale(	(float) (lScaleX / lMaxScale),
														(float) (lScaleY / lMaxScale),
														(float) (lScaleZ / lMaxScale));/**/

		// lInvVolumeMatrix.mult(lEulerMatrix);

		// lInvVolumeMatrix.transpose();

		return lModelViewMatrix;
	}

	private void applyControllersTransform()
	{
		if (getRotationControllers().size() > 0)
		{
			final Quaternion lQuaternion = new Quaternion();

			for (final RotationControllerInterface lRotationController : getRotationControllers())
				if (lRotationController.isActive())
				{
					if (lRotationController instanceof RotationControllerWithRenderNotification)
					{
						final RotationControllerWithRenderNotification lRenderNotification = (RotationControllerWithRenderNotification) lRotationController;
						lRenderNotification.notifyRender(this);
					}
					lQuaternion.mult(lRotationController.getQuaternion());

					notifyChangeOfVolumeRenderingParameters();
				}

			lQuaternion.mult(getQuaternion());
			setQuaternion(lQuaternion);
		}
	}

	private boolean isOverlay2DChanged()
	{
		boolean lHasAnyChanged = false;
		for (final Overlay lOverlay : mOverlayMap.values())
			if (lOverlay instanceof Overlay2D)
			{
				final Overlay2D lOverlay2D = (Overlay2D) lOverlay;
				lHasAnyChanged |= lOverlay2D.hasChanged2D();
			}
		return lHasAnyChanged;
	}

	private boolean isOverlay3DChanged()
	{
		boolean lHasAnyChanged = false;
		for (final Overlay lOverlay : mOverlayMap.values())
			if (lOverlay instanceof Overlay3D)
			{
				final Overlay3D lOverlay3D = (Overlay3D) lOverlay;
				lHasAnyChanged |= lOverlay3D.hasChanged3D();
			}
		return lHasAnyChanged;
	}

	private void renderOverlays2D(final GL lGL,
																final GLMatrix pProjectionMatrix)
	{
		try
		{

			for (final Overlay lOverlay : mOverlayMap.values())
				if (lOverlay instanceof Overlay2D)
				{
					final Overlay2D lOverlay2D = (Overlay2D) lOverlay;
					try
					{
						lOverlay2D.render2D(lGL,
																getViewportWidth(),
																getViewportHeight(),
																pProjectionMatrix);
					}
					catch (final Throwable e)
					{
						e.printStackTrace();
					}
				}

			GLError.printGLErrors(lGL, "AFTER OVERLAYS");
		}
		catch (final Throwable e)
		{
			e.printStackTrace();
		}
	}

	private void renderOverlays3D(final GL lGL,
																final GLMatrix pProjectionMatrix,
																final GLMatrix pModelViewMatrix)
	{
		try
		{
			for (final Overlay lOverlay : mOverlayMap.values())
				if (lOverlay instanceof Overlay3D)
				{
					final Overlay3D lOverlay3D = (Overlay3D) lOverlay;
					try
					{
						lOverlay3D.render3D(lGL,
																getViewportWidth(),
																getViewportHeight(),
																pProjectionMatrix,
																pModelViewMatrix);
					}
					catch (final Throwable e)
					{
						e.printStackTrace();
					}
				}

			GLError.printGLErrors(lGL, "AFTER OVERLAYS");
		}
		catch (final Throwable e)
		{
			e.printStackTrace();
		}
	}

	/**
	 * @param pModelViewMatrix
	 *          Model-mViewMatrix matrix as float array
	 * @param pProjectionMatrix
	 *          Projection matrix as float array
	 * @param pPhase
	 * @param pClearBuffer
	 * @return boolean array indicating for each layer if it was updated.
	 */
	protected abstract boolean[] renderVolume(final float[] pModelViewMatrix,
																						final float[] pProjectionMatrix);

	/**
	 * Updates the display of the framerate.
	 */
	private void updateFrameRateDisplay()
	{
		if (mNewtCanvasAWT != null)
			return;

		/*step++;
		final long currentTime = System.nanoTime();
		if (prevTimeNS == -1)
		{
			prevTimeNS = currentTime;
		}
		final long diff = currentTime - prevTimeNS;
		if (diff > 1e9)
		{
			final double fps = (diff / 1e9) * step;
			String t = getWindowName() + " (";
			t += String.format("%.2f", fps) + " fps)";
			setWindowTitle(t);
			prevTimeNS = currentTime;
			step = 0;
		}/**/
	}

	/**
	 * @param pTitleString
	 */
	private void setWindowTitle(final String pTitleString)
	{
		mClearGLWindow.setWindowTitle(pTitleString);
	}

	/* (non-Javadoc)
	 * @see com.jogamp.opengl.GLEventListener#reshape(com.jogamp.opengl.GLAutoDrawable, int, int, int, int)
	 */
	@Override
	public void reshape(final GLAutoDrawable pDrawable,
											final int x,
											final int y,
											int pWidth,
											int pHeight)
	{
		try
		{
			getAdaptiveLODController().notifyUserInteractionInProgress();

			// final GL lGl = pDrawable.getGL();
			// lGl.glClearColor(0, 0, 0, 1);
			// lGl.glClear(GL.GL_COLOR_BUFFER_BIT);

			mViewportX = x;
			mViewportY = y;
			setViewportWidth(pWidth);
			setViewportHeight(pHeight);

			if (pHeight < 16)
				pHeight = 16;

			if (pWidth < 16)
				pWidth = 16;

			final float lAspectRatio = (1.0f * pWidth) / pHeight;

			if (lAspectRatio >= 1)
				mQuadProjectionMatrix.setOrthoProjectionMatrix(	-1,
																												1,
																												-1	/ lAspectRatio,
																												1 / lAspectRatio,
																												0,
																												1000);
			else
				mQuadProjectionMatrix.setOrthoProjectionMatrix(	-lAspectRatio,
																												lAspectRatio,
																												-1,
																												1,
																												0,
																												1000);/**/

			final int lMaxVolumeDimension = (int) max(getVolumeSizeX(),
																								max(getVolumeSizeY(),
																										getVolumeSizeZ()));

			final int lMaxTextureWidth = min(	mMaxRenderWidth,
																				2 * lMaxVolumeDimension);
			final int lMaxTextureHeight = min(mMaxRenderHeight,
																				2 * lMaxVolumeDimension);

			final int lCandidateTextureWidth = ((min(	lMaxTextureWidth,
																								getViewportWidth()) / 128) * 128);
			final int lCandidateTextureHeight = ((min(lMaxTextureHeight,
																								getViewportHeight()) / 128) * 128);

			if (lCandidateTextureWidth == 0 || lCandidateTextureHeight == 0)
				return;

			float lRatioWidth = ((float) mRenderWidth) / lCandidateTextureWidth;
			float lRatioHeight = ((float) mRenderHeight) / lCandidateTextureHeight;
			float lRatioAspect = (((float) mRenderWidth) / mRenderHeight) / ((float) lCandidateTextureWidth / lCandidateTextureHeight);

			if (lRatioWidth > 0 && lRatioWidth < 1)
				lRatioWidth = 1f / lRatioWidth;

			if (lRatioHeight > 0 && lRatioHeight < 1)
				lRatioHeight = 1f / lRatioHeight;

			if (lRatioAspect > 0 && lRatioAspect < 1)
				lRatioAspect = 1f / lRatioAspect;

			/*System.out.format("modified ratios: (%g,%g) \n",
												lRatioWidth,
												lRatioHeight);/**/

			if (lRatioWidth > cTextureDimensionChangeRatioThreshold || lRatioHeight > cTextureDimensionChangeRatioThreshold
					|| lRatioAspect > cTextureAspectChangeRatioThreshold)
			{
				mRenderWidth = lCandidateTextureWidth;
				mRenderHeight = lCandidateTextureHeight;
				mUpdateTextureWidthHeight = true;

				/*System.out.format("resizing texture: (%d,%d) \n",
													mRenderWidth,
													mRenderHeight);/**/
			}

		}
		catch (final Throwable e)
		{
			e.printStackTrace();
		}

	}

	/**
	 * Implementation of GLEventListener - not used
	 */
	@Override
	public void dispose(final GLAutoDrawable arg0)
	{
		mClearGLWindow.stop();
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#toggleFullScreen()
	 */
	@Override
	public void toggleFullScreen()
	{
		try
		{
			if (mClearGLWindow.isFullscreen())
			{
				if (mLastWindowWidth > 0 && mLastWindowHeight > 0)
					mClearGLWindow.setSize(mLastWindowWidth, mLastWindowHeight);
				mClearGLWindow.setFullscreen(false);
			}
			else
			{
				mLastWindowWidth = getWindowWidth();
				mLastWindowHeight = getWindowHeight();
				mClearGLWindow.setFullscreen(true);
			}
			// notifyUpdateOfVolumeRenderingParameters();
			requestDisplay();
		}
		catch (final Exception e)
		{
			e.printStackTrace();
		}
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#isFullScreen()
	 */
	@Override
	public boolean isFullScreen()
	{
		return mClearGLWindow.isFullscreen();
	}

	/**
	 * Toggles box display.
	 */
	@Override
	public void toggleBoxDisplay()
	{
		mOverlayMap.get("box").toggle();
	}

	/**
	 * Toggles recording of rendered window frames.
	 */
	@Override
	public void toggleRecording()
	{
		mGLVideoRecorder.toggleActive();
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.DisplayRequestInterface#requestDisplay()
	 */
	@Override
	public void requestDisplay()
	{
		mRequestDisplay = true;
		// NOT NEEDED ANYMORE
		// getAdaptiveLODController().requestDisplay();
		// notifyChangeOfVolumeRenderingParameters();
	}

	@Override
	public void addOverlay(Overlay pOverlay)
	{
		mOverlayMap.put(pOverlay.getName(), pOverlay);
	}

	@Override
	public Collection<Overlay> getOverlays()
	{
		return mOverlayMap.values();
	}

	/**
	 * Notifies eye ray listeners.
	 * 
	 * @param pRenderer
	 *          renderer that calls listeners
	 * @param pMouseEvent
	 *          associated mouse event.
	 * @return true if event captured
	 */
	public boolean notifyEyeRayListeners(	ClearGLVolumeRenderer pRenderer,
																				MouseEvent pMouseEvent)
	{
		if (mEyeRayListenerList.isEmpty())
			return false;

		final int lX = pMouseEvent.getX();
		final int lY = pMouseEvent.getY();

		final GLMatrix lInverseModelViewMatrix = getModelViewMatrix().clone()
																																	.invert();
		final GLMatrix lInverseProjectionMatrix = getClearGLWindow().getProjectionMatrix()
																																.clone()
																																.invert();

		final GLMatrix lAspectRatioCorrectedProjectionMatrix = getAspectRatioCorrectedProjectionMatrix().invert();

		// lInverseModelViewMatrix.transpose();
		// lInverseProjectionMatrix.invert();
		// lInverseProjectionMatrix.transpose();

		final EyeRay lEyeRay = ScreenToEyeRay.convert(getViewportWidth(),
																									getViewportHeight(),
																									lX,
																									lY,
																									lInverseModelViewMatrix,
																									lAspectRatioCorrectedProjectionMatrix);

		boolean lPreventOtherDisplayChanges = false;

		for (final EyeRayListener lEyeRayListener : mEyeRayListenerList)
		{
			lPreventOtherDisplayChanges |= lEyeRayListener.notifyEyeRay(pRenderer,
																																	pMouseEvent,
																																	lEyeRay);
			if (lPreventOtherDisplayChanges)
				break;
		}
		return lPreventOtherDisplayChanges;
	}

	@Override
	public void disableClose()
	{
		mClearGLWindow.setDefaultCloseOperation(WindowClosingMode.DO_NOTHING_ON_CLOSE);
	}

	private boolean anyIsTrue(final boolean[] pBooleanArray)
	{
		for (final boolean lBoolean : pBooleanArray)
			if (lBoolean)
				return true;
		return false;
	}

	/**
	 * @return the mNewtCanvasAWT
	 */
	@Override
	public NewtCanvasAWT getNewtCanvasAWT()
	{
		return mNewtCanvasAWT;
	}

	public int getViewportHeight()
	{
		return mViewportHeight;
	}

	public void setViewportHeight(int pViewportHeight)
	{
		mViewportHeight = pViewportHeight;
	}

	public int getViewportWidth()
	{
		return mViewportWidth;
	}

	public void setViewportWidth(int pViewportWidth)
	{
		mViewportWidth = pViewportWidth;
	}

}
