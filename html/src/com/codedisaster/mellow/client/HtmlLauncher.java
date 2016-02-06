package com.codedisaster.mellow.client;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.backends.gwt.GwtApplication;
import com.badlogic.gdx.backends.gwt.GwtApplicationConfiguration;
import com.codedisaster.mellow.MellowGame;

public class HtmlLauncher extends GwtApplication {

        @Override
        public GwtApplicationConfiguration getConfig () {
                return new GwtApplicationConfiguration(MellowGame.SCREEN_WIDTH, MellowGame.SCREEN_HEIGHT);
        }

        @Override
        public ApplicationListener createApplicationListener () {
                return new MellowGame();
        }
}