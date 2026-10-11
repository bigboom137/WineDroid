/*
 * WineDroid home screen shortcuts
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 */

package com.winedroid;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import org.winehq.wine.WineActivity;

/* Home screens start pinned shortcuts with FLAG_ACTIVITY_CLEAR_TASK. Started
 * in the app's task, that would destroy a running WineActivity, which stops
 * Wine. So shortcuts open this activity in a task of its own, and it hands
 * the program to WineActivity: a running one runs it next to the others. */
public class ShortcutActivity extends Activity
{
    public static final String ACTION_RUN = "com.winedroid.action.RUN";

    @Override
    protected void onCreate( Bundle savedInstanceState )
    {
        super.onCreate( savedInstanceState );
        Intent intent = getIntent();
        String cmdline = intent.getStringExtra( WineActivity.EXTRA_CMDLINE );
        String runtime = intent.getStringExtra( WineActivity.EXTRA_RUNTIME );
        String running = WineActivity.getRunningRuntime();

        if (savedInstanceState != null || cmdline == null || runtime == null)
        {
            finish();
            return;
        }
        if (running != null && !running.equals( runtime ))
        {
            /* Wine can only be loaded once per process */
            Toast.makeText( this, "This shortcut needs " + runtime + ", but WineDroid is running "
                            + running + ". Stop Wine first.", Toast.LENGTH_LONG ).show();
            finish();
            return;
        }
        Intent run = new Intent( this, WineActivity.class );
        run.putExtra( WineActivity.EXTRA_CMDLINE, cmdline );
        run.putExtra( WineActivity.EXTRA_RUNTIME, runtime );
        startActivity( run );
        finish();
    }
}
