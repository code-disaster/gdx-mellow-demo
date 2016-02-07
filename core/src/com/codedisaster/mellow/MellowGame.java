package com.codedisaster.mellow;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.graphics.glutils.*;
import com.badlogic.gdx.maps.tiled.TiledMap;
import com.badlogic.gdx.maps.tiled.TmxMapLoader;
import com.badlogic.gdx.maps.tiled.renderers.OrthogonalTiledMapRenderer;
import com.badlogic.gdx.math.*;
import com.badlogic.gdx.scenes.scene2d.*;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

import static com.badlogic.gdx.Input.Keys;

public class MellowGame extends ApplicationAdapter {

	private enum Mode {
		// use raw floating point position
		NaiveUseRawPosition,
		// snap to nearest pixel position (classic behavior)
		ClassicSnapToPixelPosition,
		// displacement offset in shader
		UpScaleShader,
		// displacement offset in shader + linear texture filtering (bad idea)
		UpScaleShaderWithLinearTextureFilter,
		// displacement offset + bilinear filter in shader
		UpScaleAndBilinearFilterShader
	}

	/*
		The demo uses hardcoded screen and framebuffer sizes.
	 */

	private static final int FRAMEBUFFER_WIDTH = 320;
	private static final int FRAMEBUFFER_HEIGHT = 256;
	private static final int UPSCALE = 4;

	public static final int SCREEN_WIDTH = FRAMEBUFFER_WIDTH * UPSCALE;
	public static final int SCREEN_HEIGHT = FRAMEBUFFER_HEIGHT * UPSCALE;

	/*
		World scale of 1/16 pixels. 20x16 tiles visible on screen.
	 */

	private static final float CAMERA_WIDTH = 20.0f;
	private static final float CAMERA_HEIGHT = 16.0f;

	private static final float CAMERA_BORDER_DIST_X = 0.75f * CAMERA_WIDTH;
	private static final float CAMERA_BORDER_DIST_Y = 0.75f * CAMERA_HEIGHT;

	private static final float WORLD_UNIT_SCALE = 16.0f;
	private static final float WORLD_UNIT_INV_SCALE = 1.0f / WORLD_UNIT_SCALE;

	/*
		Acceleration and dampen factors to make WASD movement non-linear.
	 */

	private static final float WASD_TRANSLATE_ACCEL = 1.0f;
	private static final float WASD_TRANSLATE_DAMPEN = 0.5f;

	private SpriteBatch batch;
	private ShaderProgram mellowShader;

	private TiledMap tiledMap;
	private OrthogonalTiledMapRenderer tiledMapRenderer;

	private FrameBuffer sceneFrameBuffer;
	private OrthographicCamera sceneCamera;
	private OrthographicCamera viewportCamera;

	private int fps;
	private GlyphLayout fpsGlyphs = new GlyphLayout();

	private Stage ui;
	private Skin uiSkin;
	private BitmapFont font;
	private SelectBox<String> modeSelectBox;
	private CheckBox lookAroundBox;
	private CheckBox slowFPSBox;

	private InputMultiplexer multiplexer;
	private Input input = new Input();

	private int mapWidth;
	private int mapHeight;
	private Vector2 cameraPosition = new Vector2();
	private Vector2 cameraFocus = new Vector2();
	private Vector2 cameraTranslate = new Vector2();
	private Vector2 cameraDirection = new Vector2();

	private boolean cameraClickScroll = false;
	private Vector2 cameraClickStart = new Vector2();
	private Vector2 cameraClickTarget = new Vector2();

	private boolean lookAround = true;
	private Mode mellowMode = Mode.ClassicSnapToPixelPosition;
	private boolean slowFPS = false;

	private Vector2[] tmpVec2 = new Vector2[] { new Vector2(), new Vector2() };
	private Vector3 tmpVec3 = new Vector3();

	@Override
	public void create () {

		batch = new SpriteBatch();

		mellowShader = new ShaderProgram(
				Gdx.files.internal("shaders/mellow.vsh"),
				Gdx.files.internal("shaders/mellow.fsh"));

		if (!mellowShader.isCompiled()) {
			throw new GdxRuntimeException(mellowShader.getLog());
		}

		tiledMap = new TmxMapLoader().load("maps/test2.tmx");
		tiledMapRenderer = new OrthogonalTiledMapRenderer(tiledMap, WORLD_UNIT_INV_SCALE, batch);

		mapWidth = tiledMap.getProperties().get("width", int.class);
		mapHeight = tiledMap.getProperties().get("height", int.class);

		// initial camera position at center of map

		cameraPosition.set(0.5f * mapWidth, 0.5f * mapHeight);
		cameraFocus.set(cameraPosition);

		// framebuffer and cameras

		sceneCamera = new OrthographicCamera(CAMERA_WIDTH, CAMERA_HEIGHT);
		sceneCamera.position.set(cameraFocus, 0f);
		sceneCamera.update();

		sceneFrameBuffer = new FrameBuffer(Pixmap.Format.RGB888, FRAMEBUFFER_WIDTH, FRAMEBUFFER_HEIGHT, false);
		sceneFrameBuffer.getColorBufferTexture().setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);

		viewportCamera = new OrthographicCamera(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
		viewportCamera.position.set(0.5f * viewportCamera.viewportWidth, 0.5f * viewportCamera.viewportHeight, 0.0f);
		viewportCamera.update();

		// demo UI

		ui = new Stage(new ScreenViewport(viewportCamera));
		uiSkin = new Skin(Gdx.files.internal("ui/uiskin.json"));
		font = uiSkin.getFont("default-font");

		Table table = new Table(uiSkin);
		table.top().left().pad(8).setFillParent(true);

		Label modeLabel = new Label("[M/N]:", uiSkin);
		table.add(modeLabel).padRight(8);

		modeSelectBox = new SelectBox<String>(uiSkin);

		String[] options = new String[Mode.values().length];
		for (int i = 0; i < options.length; i++) {
			options[i] = Mode.values()[i].name();
		}

		modeSelectBox.setItems(options);
		modeSelectBox.setSelected(mellowMode.name());

		modeSelectBox.addListener(new ChangeListener() {
			@Override
			public void changed(ChangeEvent event, Actor actor) {
				changeFilterMode(Mode.valueOf(modeSelectBox.getSelected()));
			}
		});

		table.add(modeSelectBox).row();

		Label lookAroundLabel = new Label("[L]:", uiSkin);
		table.add(lookAroundLabel).padRight(8);

		lookAroundBox = new CheckBox("MouseLook", uiSkin);
		lookAroundBox.setChecked(lookAround);
		lookAroundBox.addListener(new ClickListener() {
			@Override
			public void clicked(InputEvent event, float x, float y) {
				lookAround = lookAroundBox.isChecked();
			}
		});

		table.add(lookAroundBox).align(Align.left).row();

		if (Gdx.app.getType() == Application.ApplicationType.Desktop) {

			Label slowFPSLabel = new Label("[F]:", uiSkin);
			table.add(slowFPSLabel).padRight(8);

			slowFPSBox = new CheckBox("Low FPS", uiSkin);
			slowFPSBox.setChecked(slowFPS);
			slowFPSBox.addListener(new ClickListener() {
				@Override
				public void clicked(InputEvent event, float x, float y) {
					slowFPS = slowFPSBox.isChecked();
				}
			});

			table.add(slowFPSBox).align(Align.left).row();
		}

		ui.addActor(table);

		// input

		multiplexer = new InputMultiplexer(ui, input);
		Gdx.input.setInputProcessor(multiplexer);
	}

	@Override
	public void dispose() {
		Gdx.input.setInputProcessor(null);
		uiSkin.dispose();
		ui.dispose();
		tiledMapRenderer.dispose();
		tiledMap.dispose();
		mellowShader.dispose();
		batch.dispose();
	}

	@Override
	public void render () {

		int fps = Gdx.graphics.getFramesPerSecond();
		if (this.fps != fps) {
			fpsGlyphs.setText(font, "[fps: " + fps + "]");
			this.fps = fps;
		}

		if (slowFPS) {
			try {
				Thread.sleep(24);
			} catch (InterruptedException ignored) {
			}
		}

		float dT = Gdx.graphics.getDeltaTime();

		// scroll on mouse click, kind of hack'ish by faking WASD movement until target is
		// *about* to be reached

		if (cameraClickScroll) {
			tmpVec2[0].set(cameraPosition).sub(cameraClickStart).nor();
			tmpVec2[1].set(cameraClickTarget).sub(cameraPosition).nor();

			if (tmpVec2[0].len() < WORLD_UNIT_INV_SCALE || tmpVec2[0].dot(tmpVec2[1]) > 0.0f) {
				cameraDirection.set(tmpVec2[1]).nor();
			} else {
				cameraClickScroll = false;
				cameraDirection.set(Vector2.Zero);
			}
		}

		// accelerate in WASD direction, and decelerate in opposite direction

		tmpVec2[0].set(cameraDirection).nor().scl(WASD_TRANSLATE_ACCEL);
		cameraTranslate.mulAdd(tmpVec2[0], dT * WASD_TRANSLATE_ACCEL);

		tmpVec2[1].set(cameraTranslate).nor().scl(-cameraDirection.nor().len());
		cameraTranslate.mulAdd(tmpVec2[1], dT * WASD_TRANSLATE_DAMPEN);

		cameraTranslate.lerp(Vector2.Zero, 0.1f * (1.0f - cameraDirection.nor().len()));

		// keep camera position inside map (with 1/2 screen size for border to adjust for mouse look)

		cameraPosition.add(cameraTranslate);

		if ((cameraPosition.x <= CAMERA_BORDER_DIST_X) || (cameraPosition.x >= mapWidth - CAMERA_BORDER_DIST_X)) {
			cameraTranslate.x = 0.0f;
		}

		if ((cameraPosition.y <= CAMERA_BORDER_DIST_Y) || (cameraPosition.y >= mapHeight - CAMERA_BORDER_DIST_Y)) {
			cameraTranslate.y = 0.0f;
		}

		cameraPosition.x = MathUtils.clamp(cameraPosition.x, CAMERA_BORDER_DIST_X, mapWidth - CAMERA_BORDER_DIST_X);
		cameraPosition.y = MathUtils.clamp(cameraPosition.y, CAMERA_BORDER_DIST_Y, mapHeight - CAMERA_BORDER_DIST_Y);

		// mouse look

		int mX = Gdx.input.getX();
		int mY = Gdx.graphics.getHeight() - 1 - Gdx.input.getY();

		float dX = 0.0f;
		float dY = 0.0f;

		if (lookAround) {
			dX = 8.0f * (mX - 0.5f * SCREEN_WIDTH) / SCREEN_WIDTH;
			dY = 8.0f * (mY - 0.5f * SCREEN_HEIGHT) / SCREEN_HEIGHT;
		}

		cameraFocus.set(cameraPosition);

		float sceneX = cameraFocus.x + dX;
		float sceneY = cameraFocus.y + dY;

		// snap camera position to full pixels, avoiding floating point error artifacts

		float sceneIX = sceneX;
		float sceneIY = sceneY;

		if (mellowMode != Mode.NaiveUseRawPosition) {
			sceneIX = MathUtils.floor(sceneX * WORLD_UNIT_SCALE) / WORLD_UNIT_SCALE;
			sceneIY = MathUtils.floor(sceneY * WORLD_UNIT_SCALE) / WORLD_UNIT_SCALE;
		}

		// calculate displacement offset: for UPSCALE=4, this results in (integer) offsets in [0..3]

		float upscaleOffsetX = 0.0f;
		float upscaleOffsetY = 0.0f;

		if (mellowMode == Mode.UpScaleShader
				|| mellowMode == Mode.UpScaleShaderWithLinearTextureFilter
				|| mellowMode == Mode.UpScaleAndBilinearFilterShader) {
			upscaleOffsetX = (sceneX - sceneIX) * WORLD_UNIT_SCALE * UPSCALE;
			upscaleOffsetY = (sceneY - sceneIY) * WORLD_UNIT_SCALE * UPSCALE;
		}

		// subpixel interpolation in [0..1]: basically the delta between two displacement offset values

		float subpixelX = 0.0f;
		float subpixelY = 0.0f;

		if (mellowMode == Mode.UpScaleAndBilinearFilterShader) {
			subpixelX = upscaleOffsetX - MathUtils.floor(upscaleOffsetX);
			subpixelY = upscaleOffsetY - MathUtils.floor(upscaleOffsetY);
		}

		upscaleOffsetX -= subpixelX;
		upscaleOffsetY -= subpixelY;

		// set camera for rendering to snapped position

		sceneCamera.position.set(sceneIX, sceneIY, 0.0f);
		sceneCamera.update();

		// render tilemap to framebuffer

		Gdx.gl20.glEnable(GL20.GL_SCISSOR_TEST); // re-enabled each frame because UI changes GL state
		HdpiUtils.glScissor(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);

		Gdx.gl.glClearColor(0.0f, 0.0f, 0.3f, 1.0f);
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

		sceneFrameBuffer.begin();

		tiledMapRenderer.setView(sceneCamera);
		tiledMapRenderer.render();

		sceneFrameBuffer.end();

		// render upscaled framebuffer to backbuffer
		// viewport/scissor adjust for artifacts at right/top pixel columns/lines

		HdpiUtils.glViewport(UPSCALE / 2, UPSCALE / 2, SCREEN_WIDTH, SCREEN_HEIGHT);
		HdpiUtils.glScissor(UPSCALE / 2, UPSCALE / 2, SCREEN_WIDTH - UPSCALE, SCREEN_HEIGHT - UPSCALE);

		batch.begin();
		batch.setShader(mellowShader);
		batch.setProjectionMatrix(viewportCamera.combined);
		mellowShader.setUniformf("u_textureSizes", FRAMEBUFFER_WIDTH, FRAMEBUFFER_HEIGHT, UPSCALE, 0.0f);
		mellowShader.setUniformf("u_sampleProperties", subpixelX, subpixelY, upscaleOffsetX, upscaleOffsetY);
		batch.draw(sceneFrameBuffer.getColorBufferTexture(), 0, SCREEN_HEIGHT, SCREEN_WIDTH, -SCREEN_HEIGHT);
		batch.end();

		// reset scissor

		batch.setShader(null);
		HdpiUtils.glScissor(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);

		// render UI

		ui.act();
		ui.draw();

		batch.begin();
		font.draw(batch, fpsGlyphs, 8, 32);
		batch.end();
	}

	private void changeFilterMode(Mode mode) {
		mellowMode = mode;
		modeSelectBox.setSelected(mode.name());

		if (mellowMode == Mode.UpScaleShaderWithLinearTextureFilter) {
			sceneFrameBuffer.getColorBufferTexture().setFilter(
					Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
		} else {
			sceneFrameBuffer.getColorBufferTexture().setFilter(
					Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
		}
	}

	private class Input extends InputAdapter {

		@Override
		public boolean keyDown(int keycode) {
			switch (keycode) {
				case Keys.A:
					MellowGame.this.cameraDirection.x = -1.0f;
					return true;
				case Keys.D:
					MellowGame.this.cameraDirection.x = 1.0f;
					return true;
				case Keys.W:
					MellowGame.this.cameraDirection.y = 1.0f;
					return true;
				case Keys.S:
					MellowGame.this.cameraDirection.y = -1.0f;
					return true;
			}

			return false;
		}

		@Override
		public boolean keyUp(int keycode) {
			switch (keycode) {
				case Keys.A:
					MellowGame.this.cameraDirection.x = 0.0f;
					return true;
				case Keys.D:
					MellowGame.this.cameraDirection.x = 0.0f;
					return true;
				case Keys.W:
					MellowGame.this.cameraDirection.y = 0.0f;
					return true;
				case Keys.S:
					MellowGame.this.cameraDirection.y = 0.0f;
					return true;
				case Keys.M: {
					Mode mode = Mode.values()[(mellowMode.ordinal() + 1) % Mode.values().length];
					changeFilterMode(mode);
					return true;
				}
				case Keys.N: {
					Mode mode = mellowMode == Mode.UpScaleShader ? Mode.UpScaleAndBilinearFilterShader : Mode.UpScaleShader;
					changeFilterMode(mode);
					return true;
				}
				case Keys.L: {
					lookAround = !lookAround;
					lookAroundBox.setChecked(lookAround);
					return true;
				}
				case Keys.F: {
					slowFPS = !slowFPS;
					if (slowFPSBox != null) {
						slowFPSBox.setChecked(slowFPS);
					}
					return true;
				}
			}

			return false;
		}

		@Override
		public boolean touchUp(int screenX, int screenY, int pointer, int button) {
			if (button == 0) {

				sceneCamera.unproject(tmpVec3.set(screenX, screenY, 0.0f));

				float x = tmpVec3.x;
				float y = tmpVec3.y;

				if (!cameraClickScroll) {
					cameraClickStart.set(cameraPosition);
				}

				cameraClickTarget.set(x, y);

				cameraClickScroll = true;
			}

			return false;
		}
	}

}
