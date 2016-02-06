package com.codedisaster.mellow.desktop;

import com.badlogic.gdx.backends.lwjgl.LwjglApplication;
import com.badlogic.gdx.backends.lwjgl.LwjglApplicationConfiguration;
import com.codedisaster.mellow.MellowGame;

public class MellowGameDesktop {
	public static void main (String[] arg) {
		LwjglApplicationConfiguration config = new LwjglApplicationConfiguration();

		config.width = MellowGame.SCREEN_WIDTH;
		config.height = MellowGame.SCREEN_HEIGHT;
		config.resizable = false;
		config.title = "Mellow libGDX Demo";

		new LwjglApplication(new MellowGame(), config);
	}
}
