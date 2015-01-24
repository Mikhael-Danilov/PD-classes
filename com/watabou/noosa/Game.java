/*
 * Copyright (C) 2012-2014  Oleg Dolya
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.watabou.noosa;

import java.util.ArrayList;
import java.util.Map.Entry;
import java.util.TreeMap;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

import com.watabou.glscripts.Script;
import com.watabou.gltextures.TextureCache;
import com.watabou.input.Keys;
import com.watabou.input.Touchscreen;
import com.watabou.noosa.audio.Music;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.BitmapCache;
import com.watabou.utils.SystemTime;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.media.AudioManager;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLSurfaceView.EGLContextFactory;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;

public class Game extends Activity implements GLSurfaceView.Renderer,
		View.OnTouchListener {

	public static Game instance;
	private static Context context;

	// Actual size of the screen
	public static int width;
	public static int height;

	// Density: mdpi=1, hdpi=1.5, xhdpi=2...
	public static float density = 1;

	public static String version;

	// Current scene
	protected Scene scene;
	// New scene we are going to switch to
	protected Scene requestedScene;
	// true if scene switch is requested
	protected boolean requestedReset = true;
	// New scene class
	protected Class<? extends Scene> sceneClass;

	// Current time in milliseconds
	protected long now;
	// Milliseconds passed since previous update
	protected long step;

	public static float timeScale = 1f;
	public static float elapsed = 0f;

	protected GLSurfaceView view;
	protected SurfaceHolder holder;

	// Accumulated touch events
	protected ArrayList<MotionEvent> motionEvents = new ArrayList<MotionEvent>();

	// Accumulated key events
	protected ArrayList<KeyEvent> keysEvents = new ArrayList<KeyEvent>();

	public Game(Class<? extends Scene> c) {
		super();
		sceneClass = c;
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Criado para manter o contexto e poder fazer a busca dos resources
		context = getApplicationContext();

		BitmapCache.context = TextureCache.context = instance = this;

		DisplayMetrics m = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getMetrics(m);
		density = m.density;

		try {
			version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
		} catch (NameNotFoundException e) {
			version = "???";
		}

		setVolumeControlStream(AudioManager.STREAM_MUSIC);

		view = new GLSurfaceView(this);
		// view.setEGLContextClientVersion( 2 );
		view.setEGLContextFactory(new glContextFactory());
		view.setEGLConfigChooser(new glConfigChoser());
		view.setRenderer(this);
		view.setOnTouchListener(this);
		setContentView(view);
	}

	@Override
	public void onResume() {
		super.onResume();

		now = 0;
		view.onResume();

		Music.INSTANCE.resume();
		Sample.INSTANCE.resume();
	}

	@Override
	public void onPause() {
		super.onPause();

		if (scene != null) {
			scene.pause();
		}

		view.onPause();
		Script.reset();

		Music.INSTANCE.pause();
		Sample.INSTANCE.pause();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		destroyGame();

		Music.INSTANCE.mute();
		Sample.INSTANCE.reset();
	}

	@SuppressLint({ "Recycle", "ClickableViewAccessibility" })
	@Override
	public boolean onTouch(View view, MotionEvent event) {
		synchronized (motionEvents) {
			motionEvents.add(MotionEvent.obtain(event));
		}
		return true;
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {

		if (keyCode == Keys.VOLUME_DOWN || keyCode == Keys.VOLUME_UP) {

			return false;
		}

		synchronized (motionEvents) {
			keysEvents.add(event);
		}
		return true;
	}

	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {

		if (keyCode == Keys.VOLUME_DOWN || keyCode == Keys.VOLUME_UP) {

			return false;
		}

		synchronized (motionEvents) {
			keysEvents.add(event);
		}
		return true;
	}

	@Override
	public void onDrawFrame(GL10 gl) {

		if (width == 0 || height == 0) {
			return;
		}

		SystemTime.tick();
		long rightNow = SystemTime.now;
		step = (now == 0 ? 0 : rightNow - now);
		now = rightNow;

		step();

		NoosaScript.get().resetCamera();
		GLES20.glScissor(0, 0, width, height);
		GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
		draw();
	}

	@Override
	public void onSurfaceChanged(GL10 gl, int width, int height) {

		GLES20.glViewport(0, 0, width, height);

		Game.width = width;
		Game.height = height;

	}

	@Override
	public void onSurfaceCreated(GL10 gl, EGLConfig config) {
		GLES20.glEnable(GL10.GL_BLEND);
		// For premultiplied alpha:
		// GLES20.glBlendFunc( GL10.GL_ONE, GL10.GL_ONE_MINUS_SRC_ALPHA );
		GLES20.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA);

		GLES20.glEnable(GL10.GL_SCISSOR_TEST);

		TextureCache.reload();
	}

	protected void destroyGame() {
		if (scene != null) {
			scene.destroy();
			scene = null;
		}

		instance = null;
	}

	public static void resetScene() {
		switchScene(instance.sceneClass);
	}

	public static void switchScene(Class<? extends Scene> c) {
		instance.sceneClass = c;
		instance.requestedReset = true;
	}

	public static Scene scene() {
		return instance.scene;
	}

	protected void step() {

		if (requestedReset) {
			requestedReset = false;
			try {
				requestedScene = sceneClass.newInstance();
				switchScene();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		update();
	}

	protected void draw() {
		scene.draw();
	}

	protected void switchScene() {

		Camera.reset();

		if (scene != null) {
			scene.destroy();
		}
		scene = requestedScene;
		scene.create();

		Game.elapsed = 0f;
		Game.timeScale = 1f;
	}

	protected void update() {
		Game.elapsed = Game.timeScale * step * 0.001f;

		synchronized (motionEvents) {
			Touchscreen.processTouchEvents(motionEvents);
			motionEvents.clear();
		}
		synchronized (keysEvents) {
			Keys.processTouchEvents(keysEvents);
			keysEvents.clear();
		}

		scene.update();
		Camera.updateAll();
	}

	public static void vibrate(int milliseconds) {
		((Vibrator) instance.getSystemService(VIBRATOR_SERVICE))
				.vibrate(milliseconds);
	}

	public static String getVar(int id) {
		return context.getResources().getString(id);
	}

	public static String[] getVars(int id) {
		return context.getResources().getStringArray(id);
	}
}

class glConfigChoser implements android.opengl.GLSurfaceView.EGLConfigChooser {

	@Override
	public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
		try {
			int num[] = new int[1];
			if (!egl.eglGetConfigs(display, null, 0, num)) {
				throw new Exception("glConfigChoser GetConfigs: get num");
			}
			EGLConfig[] configList = new EGLConfig[num[0]];
			if (!egl.eglGetConfigs(display, configList, num[0], num)) {
				throw new Exception("glConfigChoser GetConfigs: get configs");
			}
			return chooseConfig(egl, display, configList);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private int getAttribute(EGL10 egl, EGLDisplay display, EGLConfig config,
			int attribute) {
		int[] value = { 0 };

		egl.eglGetConfigAttrib(display, config, attribute, value);
		return value[0];
	}

	private int formatScore(int r, int g, int b, int a, int d, int s) {
		int score = 0;

		if (r + g + b == 16) {
			score += 2;
		}

		if (r + g + b > 16) {
			score += 1;
		}

		if (d > 0) {
			score -= 1;
		}

		if (d > 16) {
			score -= 1;
		}

		if (a > 0) {
			score -= 1;
		}

		if (s > 0) {
			score -= 1;
		}

		return score;
	}

	private EGLConfig chooseConfig(EGL10 egl, EGLDisplay display,
			EGLConfig configList[]) throws Exception {

		TreeMap<Integer, Integer> formats = new TreeMap<Integer, Integer>();

		Log.i("glConfigChoser",
				String.format("config num: %d", configList.length));
		for (int i = 0; i < configList.length; i++) {
			EGLConfig config = configList[i];

			int r = getAttribute(egl, display, config, EGL10.EGL_RED_SIZE);
			int b = getAttribute(egl, display, config, EGL10.EGL_BLUE_SIZE);
			int g = getAttribute(egl, display, config, EGL10.EGL_GREEN_SIZE);

			int a = getAttribute(egl, display, config, EGL10.EGL_ALPHA_SIZE);
			int d = getAttribute(egl, display, config, EGL10.EGL_DEPTH_SIZE);

			int s = getAttribute(egl, display, config, EGL10.EGL_STENCIL_SIZE);

			formats.put(formatScore(r, g, b, a, d, s), i);

			Log.i("glConfigChoser", String.format(
					"%d -> r: %d, g: %d, b: %d, a: %d, d: %d, s: %d",
					i, r, g, b, a, d, s));
		}

		Entry<Integer, Integer> best = formats.lastEntry();

		EGLConfig config = configList[best.getValue()];

		Log.i("glConfigChoser", String.format("chosen: %d", best.getValue()));

		return config;
	}
}

class glContextFactory implements EGLContextFactory {
	private int EGL_CONTEXT_CLIENT_VERSION = 0x3098;

	public EGLContext createContext(EGL10 egl, EGLDisplay display,
			EGLConfig config) {
		int[] attrib_list = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE };

		return egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT,
				attrib_list);
	}

	public void destroyContext(EGL10 egl, EGLDisplay display,
			EGLContext context) {
		if (!egl.eglDestroyContext(display, context)) {
			Log.e("DefaultContextFactory", "display:" + display
					+ " context: " + context);
		}
	}
}