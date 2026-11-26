package com.obdscanner.app;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ObdLinkPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
