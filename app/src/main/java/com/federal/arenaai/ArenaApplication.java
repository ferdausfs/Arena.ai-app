package com.federal.arenaai;

import android.app.Application;

public class ArenaApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ArenaWebViewManager.initialize(this);
    }
}
