/*
 * WineDroid launcher
 *
 * Picks Windows programs and hands them to WineActivity, which runs them
 * with Wine compiled natively for x86_64 Android (no CPU emulation).
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 */

package com.winedroid;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.database.Cursor;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.view.Gravity;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.winehq.wine.WineActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LauncherActivity extends Activity
{
    private static final int REQUEST_PICK = 1;
    private static final int REQUEST_PERMS = 2;
    private static final int REQUEST_PICK_SHORTCUT = 3;
    private static final String PREFS = "launcher";
    private static final String KEY_RECENT = "recent";
    private static final String KEY_SHORTCUTS = "shortcuts";
    private static final String KEY_RUNTIME = "runtime";
    private static final int MAX_RECENT = 12;
    private static final int MAX_RECENT_SHOWN = 5;

    /* runtimes the APK can carry: asset directory and name shown */
    private static final String[][] RUNTIMES = {
        { WineActivity.RUNTIME_WINE, "Wine 11.0" },
        { WineActivity.RUNTIME_PROTON, "Proton 11.0" },
    };

    private static final String KEY_LAST_PLAYED = "last_played";

    private LinearLayout recent_list;
    private LinearLayout hero_box;
    private Ui.Grid library_grid;
    private LinearLayout runtime_pills;
    private TextView running_pill;
    private String runtime;

    @Override
    protected void onCreate( Bundle savedInstanceState )
    {
        super.onCreate( savedInstanceState );
        /* opening the app (from the shelf, recents or the home screen) while Windows
         * programs run returns to them; Android starts a new launcher on top of Wine
         * when the task was started by "Open with" rather than by the app icon */
        if (Intent.ACTION_MAIN.equals( getIntent().getAction() ) && !isTaskRoot()
            && WineActivity.getRunningRuntime() != null)
        {
            startActivity( new Intent( this, WineActivity.class ));
            finish();
            return;
        }
        runtime = getSharedPreferences( PREFS, MODE_PRIVATE ).getString( KEY_RUNTIME, WineActivity.RUNTIME_WINE );
        if (!hasRuntime( runtime )) runtime = WineActivity.RUNTIME_WINE;
        buildUi();
        requestStorage();
        /* not when Android recreates the launcher after Wine exited: it would run the
         * program it was opened with again, and again each time it exits */
        if (savedInstanceState == null) handleViewIntent( getIntent() );
    }

    @Override
    protected void onNewIntent( Intent intent )
    {
        super.onNewIntent( intent );
        handleViewIntent( intent );
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        refreshLibrary();
        refreshRecent();
        updateRunning();
    }

    private void buildUi()
    {
        int pad = dp( 24 );
        LinearLayout root = new LinearLayout( this );
        root.setOrientation( LinearLayout.VERTICAL );
        root.setPadding( pad, dp( 20 ), pad, pad );

        root.addView( topBar() );

        hero_box = new LinearLayout( this );
        hero_box.setOrientation( LinearLayout.VERTICAL );
        root.addView( hero_box );

        LinearLayout lib_header = new LinearLayout( this );
        lib_header.setGravity( Gravity.CENTER_VERTICAL );
        lib_header.setPadding( 0, dp( 20 ), 0, dp( 10 ));
        TextView lib_title = Ui.section( this, "Library" );
        lib_title.setPadding( 0, 0, 0, 0 );
        lib_header.addView( lib_title, new LinearLayout.LayoutParams( 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1 ));
        lib_header.addView( Ui.pill( this, "+ Add", false, new View.OnClickListener() {
            public void onClick( View v ) { addShortcutDialog(); }} ));
        root.addView( lib_header );

        library_grid = new Ui.Grid( this, 230, 14 );
        root.addView( library_grid );

        root.addView( Ui.section( this, "Programs" ));
        Ui.Grid actions = new Ui.Grid( this, 170, 10 );
        actions.addView( actionTile( "Run a program", ".exe, .msi or .bat file", false, new View.OnClickListener() {
            public void onClick( View v ) { pickProgram(); }} ));
        actions.addView( actionTile( "Wine desktop", "File manager", false, runner( "Wine desktop", "explorer.exe" )));
        actions.addView( actionTile( "Command prompt", "wineconsole", false, runner( "Command prompt", "wineconsole.exe" )));
        actions.addView( actionTile( "Wine settings", "winecfg", false, runner( "Wine settings", "winecfg.exe" )));
        actions.addView( actionTile( "Task manager", "Running programs", false, runner( "Task manager", "taskmgr.exe" )));
        actions.addView( actionTile( "Stop Wine", "Closes all programs", true, new View.OnClickListener() {
            public void onClick( View v ) { stopWine(); }} ));
        root.addView( actions );

        root.addView( Ui.section( this, "Recent" ));
        recent_list = new LinearLayout( this );
        recent_list.setOrientation( LinearLayout.VERTICAL );
        root.addView( recent_list );

        root.addView( Ui.section( this, "Settings" ));
        root.addView( mouseSpeedSetting() );

        TextView hint = Ui.text( this, "Drive D: is your phone storage and drive Z: the whole file system. "
                                 + "Tap the ⌨ button in Wine for the on-screen keyboard; touches are left "
                                 + "clicks, and USB or Bluetooth mice and keyboards work too. Long-press "
                                 + "a game for more options.", 13, Ui.TEXT_SECONDARY, false );
        hint.setPadding( 0, dp( 28 ), 0, 0 );
        hint.setLineSpacing( dp( 2 ), 1 );
        root.addView( hint );

        ScrollView scroll = new ScrollView( this );
        scroll.setBackgroundColor( Ui.BACKGROUND );
        scroll.setFillViewport( true );
        scroll.addView( root );
        setContentView( scroll );
    }

    private View topBar()
    {
        LinearLayout bar = new LinearLayout( this );
        bar.setGravity( Gravity.CENTER_VERTICAL );

        ImageView icon = new ImageView( this );
        icon.setImageResource( R.drawable.wine );
        bar.addView( icon, new LinearLayout.LayoutParams( dp( 40 ), dp( 40 )));

        LinearLayout titles = new LinearLayout( this );
        titles.setOrientation( LinearLayout.VERTICAL );
        titles.setPadding( dp( 12 ), 0, dp( 12 ), 0 );
        titles.addView( Ui.text( this, "WineDroid", 24, Ui.TEXT, true ));
        titles.addView( Ui.text( this, "Windows programs on x86_64 Android", 13, Ui.TEXT_SECONDARY, false ));
        bar.addView( titles, new LinearLayout.LayoutParams( 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1 ));

        running_pill = Ui.pill( this, "Back to Windows", true, new View.OnClickListener() {
            public void onClick( View v ) { startActivity( new Intent( LauncherActivity.this, WineActivity.class )); }} );
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams( LinearLayout.LayoutParams.WRAP_CONTENT,
                                                                      LinearLayout.LayoutParams.WRAP_CONTENT );
        lp.rightMargin = dp( 12 );
        bar.addView( running_pill, lp );

        /* each runtime has its own C: drive, so programs installed with one
         * are not visible to the other */
        runtime_pills = new LinearLayout( this );
        runtime_pills.setPadding( dp( 3 ), dp( 3 ), dp( 3 ), dp( 3 ));
        runtime_pills.setBackground( Ui.rounded( this, Ui.SURFACE, Ui.OUTLINE, 22 ));
        for (final String[] r : RUNTIMES)
        {
            if (!hasRuntime( r[0] )) continue;
            TextView t = Ui.text( this, r[1], 14, Ui.TEXT, true );
            t.setTag( r[0] );
            t.setPadding( dp( 14 ), dp( 7 ), dp( 14 ), dp( 7 ));
            t.setOnClickListener( new View.OnClickListener() {
                public void onClick( View v ) { selectRuntime( r[0] ); }} );
            runtime_pills.addView( t );
        }
        bar.addView( runtime_pills );
        checkRuntime( runtime );
        return bar;
    }

    private void updateRunning()
    {
        if (running_pill != null)
            running_pill.setVisibility( WineActivity.getRunningRuntime() != null ? View.VISIBLE : View.GONE );
    }

    private View actionTile( String title, String subtitle, boolean danger, View.OnClickListener l )
    {
        LinearLayout tile = new LinearLayout( this );
        tile.setOrientation( LinearLayout.VERTICAL );
        tile.setPadding( dp( 16 ), dp( 14 ), dp( 16 ), dp( 14 ));
        Ui.card( tile, Ui.SURFACE, Ui.OUTLINE, 12 );
        tile.addView( Ui.text( this, title, 15, danger ? Ui.DANGER : Ui.TEXT, true ));
        TextView sub = Ui.text( this, subtitle, 12, Ui.TEXT_SECONDARY, false );
        sub.setPadding( 0, dp( 2 ), 0, 0 );
        tile.addView( sub );
        tile.setOnClickListener( l );
        return tile;
    }

    /* games that hide the cursor get raw mouse motion, which Android's pointer
     * speed doesn't apply to; games that scale it to a low-resolution playfield
     * then feel slow on a high-resolution screen */
    private View mouseSpeedSetting()
    {
        final SharedPreferences prefs = getSharedPreferences( PREFS, MODE_PRIVATE );
        final float[] steps = { 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f };
        float speed = prefs.getFloat( WineActivity.PREF_MOUSE_SPEED, 1.0f );
        int pos = 2;
        for (int i = 0; i < steps.length; i++) if (Math.abs( steps[i] - speed ) < 0.01f) pos = i;

        LinearLayout box = new LinearLayout( this );
        box.setOrientation( LinearLayout.VERTICAL );
        box.setPadding( dp( 16 ), dp( 14 ), dp( 16 ), dp( 10 ));
        box.setBackground( Ui.rounded( this, Ui.SURFACE, Ui.OUTLINE, 12 ));

        LinearLayout head = new LinearLayout( this );
        head.addView( Ui.text( this, "Mouse speed in games", 15, Ui.TEXT, true ),
                      new LinearLayout.LayoutParams( 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1 ));
        final TextView value = Ui.text( this, steps[pos] + "×", 15, Ui.ACCENT, true );
        head.addView( value );
        box.addView( head );
        box.addView( Ui.text( this, "For games that hide the cursor and capture the mouse.", 12,
                              Ui.TEXT_SECONDARY, false ));

        SeekBar bar = new SeekBar( this );
        bar.setMax( steps.length - 1 );
        bar.setProgress( pos );
        bar.setProgressTintList( ColorStateList.valueOf( Ui.ACCENT ));
        bar.setThumbTintList( ColorStateList.valueOf( Ui.ACCENT ));
        bar.setPadding( 0, dp( 12 ), 0, dp( 6 ));
        bar.setOnSeekBarChangeListener( new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged( SeekBar b, int p, boolean user )
            {
                value.setText( steps[p] + "×" );
                prefs.edit().putFloat( WineActivity.PREF_MOUSE_SPEED, steps[p] ).apply();
            }
            public void onStartTrackingTouch( SeekBar b ) {}
            public void onStopTrackingTouch( SeekBar b ) {}
        });
        box.addView( bar );
        return box;
    }

    private View.OnClickListener runner( final String label, final String cmdline )
    {
        return new View.OnClickListener() {
            public void onClick( View v ) { runWine( label, cmdline, false ); }};
    }

    // Runtimes

    private boolean hasRuntime( String name )
    {
        try
        {
            String[] files = getAssets().list( name );
            return files != null && files.length > 0;
        }
        catch (IOException e) { return false; }
    }

    private String runtimeLabel( String name )
    {
        for (String[] r : RUNTIMES) if (r[0].equals( name )) return r[1];
        return name;
    }

    private void selectRuntime( final String name )
    {
        if (name.equals( runtime )) return;
        final String running = WineActivity.getRunningRuntime();
        if (running != null && !running.equals( name ))
        {
            /* Wine can only be loaded once per process */
            new AlertDialog.Builder( this )
                .setTitle( "Switch to " + runtimeLabel( name ) + "?" )
                .setMessage( runtimeLabel( running ) + " is running. Switching stops all running "
                             + "Windows programs." )
                .setPositiveButton( "Switch", new DialogInterface.OnClickListener() {
                    public void onClick( DialogInterface d, int which )
                    {
                        saveRuntime( name );
                        stopWine();
                    }} )
                .setNegativeButton( android.R.string.cancel, new DialogInterface.OnClickListener() {
                    public void onClick( DialogInterface d, int which ) { checkRuntime( runtime ); }} )
                .setOnCancelListener( new DialogInterface.OnCancelListener() {
                    public void onCancel( DialogInterface d ) { checkRuntime( runtime ); }} )
                .show();
            return;
        }
        saveRuntime( name );
        checkRuntime( name );
        refreshLibrary();
        refreshRecent();
    }

    private void saveRuntime( String name )
    {
        runtime = name;
        getSharedPreferences( PREFS, MODE_PRIVATE ).edit().putString( KEY_RUNTIME, name ).commit();
    }

    private void checkRuntime( String name )
    {
        if (runtime_pills == null) return;
        for (int i = 0; i < runtime_pills.getChildCount(); i++)
        {
            TextView t = (TextView)runtime_pills.getChildAt( i );
            boolean selected = name.equals( t.getTag() );
            t.setTextColor( selected ? Color.WHITE : Ui.TEXT_SECONDARY );
            t.setBackground( selected ? Ui.rounded( this, Ui.ACCENT, 0, 19 ) : null );
        }
    }

    private int dp( int v )
    {
        return Ui.dp( this, v );
    }

    // Running programs

    private void runWine( String label, String cmdline, boolean remember )
    {
        if (remember) addRecent( label, cmdline );
        getSharedPreferences( PREFS, MODE_PRIVATE ).edit()
            .putString( KEY_LAST_PLAYED + "." + runtime, cmdline ).apply();
        Intent intent = new Intent( this, WineActivity.class );
        intent.putExtra( WineActivity.EXTRA_CMDLINE, cmdline );
        intent.putExtra( WineActivity.EXTRA_RUNTIME, runtime );
        startActivity( intent );
    }

    private void runUnixFile( File file )
    {
        // "start /unix" accepts a host path and uses the file association, so
        // it handles .exe, .msi, .bat and documents alike.
        runWine( file.getName(), unixCmdline( file ), true );
    }

    private static String unixCmdline( File file )
    {
        return "start /unix \"" + file.getPath() + "\"";
    }

    private void stopWine()
    {
        Toast.makeText( this, "Stopping Wine", Toast.LENGTH_SHORT ).show();
        finishAffinity();
        WineActivity.stopWine();
    }

    // Picking programs

    private void pickProgram()
    {
        Intent intent = new Intent( Intent.ACTION_OPEN_DOCUMENT );
        intent.addCategory( Intent.CATEGORY_OPENABLE );
        intent.setType( "*/*" );
        startActivityForResult( intent, REQUEST_PICK );
    }

    @Override
    protected void onActivityResult( int request, int result, Intent data )
    {
        super.onActivityResult( request, result, data );
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == REQUEST_PICK) importAndRun( data.getData(), false );
        else if (request == REQUEST_PICK_SHORTCUT) importAndRun( data.getData(), true );
    }

    private void handleViewIntent( Intent intent )
    {
        if (intent == null || !Intent.ACTION_VIEW.equals( intent.getAction() ) || intent.getData() == null)
            return;
        Uri uri = intent.getData();
        if ("file".equals( uri.getScheme() )) runUnixFile( new File( uri.getPath() ));
        else importAndRun( uri, false );
        intent.setAction( null );  // don't run again on recreation
    }

    /* Programs picked through the document picker are only reachable through
     * a content URI, so copy them where Wine can see them. Installers are
     * usually a single file, which is the main use case here. */
    private void importAndRun( final Uri uri, final boolean as_shortcut )
    {
        final String name = displayName( uri );
        final File dir = new File( getFilesDir(), "imports" );
        final File dest = new File( dir, name );
        Toast.makeText( this, "Copying " + name + "…", Toast.LENGTH_SHORT ).show();
        new Thread( new Runnable() { public void run() {
            try
            {
                dir.mkdirs();
                InputStream in = getContentResolver().openInputStream( uri );
                OutputStream out = new FileOutputStream( dest );
                byte[] buffer = new byte[1 << 16];
                int n;
                while ((n = in.read( buffer )) > 0) out.write( buffer, 0, n );
                out.close();
                in.close();
                runOnUiThread( new Runnable() { public void run()
                {
                    if (as_shortcut) addShortcut( programLabel( name ), unixCmdline( dest ));
                    else runUnixFile( dest );
                }} );
            }
            catch (Exception e)
            {
                final String msg = e.toString();
                runOnUiThread( new Runnable() { public void run() {
                    new AlertDialog.Builder( LauncherActivity.this )
                        .setTitle( "Could not open " + name ).setMessage( msg )
                        .setPositiveButton( android.R.string.ok, null ).show();
                }} );
            }
        }} ).start();
    }

    private String displayName( Uri uri )
    {
        String name = null;
        Cursor c = getContentResolver().query( uri, new String[]{ OpenableColumns.DISPLAY_NAME },
                                               null, null, null );
        if (c != null)
        {
            if (c.moveToFirst()) name = c.getString( 0 );
            c.close();
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "program.exe";
        return name.replace( '/', '_' );
    }

    // Recent programs, stored per runtime as "label\tcmdline" lines

    private String recentKey()
    {
        if (WineActivity.RUNTIME_WINE.equals( runtime )) return KEY_RECENT;
        return KEY_RECENT + "." + runtime;
    }

    private List<String> loadRecent()
    {
        String s = getSharedPreferences( PREFS, MODE_PRIVATE ).getString( recentKey(), "" );
        List<String> list = new ArrayList<String>();
        if (s.length() > 0) list.addAll( Arrays.asList( s.split( "\n" )));
        return list;
    }

    private void saveRecent( List<String> list )
    {
        StringBuilder sb = new StringBuilder();
        for (String e : list)
        {
            if (sb.length() > 0) sb.append( '\n' );
            sb.append( e );
        }
        SharedPreferences.Editor ed = getSharedPreferences( PREFS, MODE_PRIVATE ).edit();
        ed.putString( recentKey(), sb.toString() ).apply();
    }

    private void addRecent( String label, String cmdline )
    {
        String entry = label + "\t" + cmdline;
        List<String> list = loadRecent();
        list.remove( entry );
        list.add( 0, entry );
        while (list.size() > MAX_RECENT) list.remove( list.size() - 1 );
        saveRecent( list );
    }

    private void refreshRecent()
    {
        recent_list.removeAllViews();
        List<String> list = loadRecent();
        if (list.isEmpty())
        {
            recent_list.addView( Ui.text( this, "Programs you open from a file show up here.", 14,
                                          Ui.TEXT_SECONDARY, false ));
            return;
        }
        for (final String entry : list.subList( 0, Math.min( list.size(), MAX_RECENT_SHOWN )))
        {
            final String[] parts = entry.split( "\t", 2 );
            if (parts.length != 2) continue;
            LinearLayout row = new LinearLayout( this );
            row.setOrientation( LinearLayout.VERTICAL );
            row.setPadding( dp( 16 ), dp( 11 ), dp( 16 ), dp( 11 ));
            Ui.card( row, Ui.SURFACE, Ui.OUTLINE, 10 );
            row.addView( Ui.text( this, programLabel( parts[0] ), 15, Ui.TEXT, true ));
            TextView path = Ui.text( this, recentPath( parts[1] ), 12, Ui.TEXT_SECONDARY, false );
            path.setSingleLine( true );
            path.setEllipsize( TextUtils.TruncateAt.MIDDLE );
            row.addView( path );
            row.setOnClickListener( runner( parts[0], parts[1] ));
            row.setOnLongClickListener( new View.OnLongClickListener() {
                public boolean onLongClick( View v )
                {
                    new AlertDialog.Builder( LauncherActivity.this )
                        .setTitle( parts[0] )
                        .setItems( new String[]{ "Add to library", "Remove from recent" },
                                   new DialogInterface.OnClickListener() {
                            public void onClick( DialogInterface d, int which )
                            {
                                if (which == 0)
                                {
                                    addShortcut( programLabel( parts[0] ), parts[1] );
                                    return;
                                }
                                List<String> l = loadRecent();
                                l.remove( entry );
                                saveRecent( l );
                                refreshRecent();
                            }} )
                        .show();
                    return true;
                }} );
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams( LinearLayout.LayoutParams.MATCH_PARENT,
                                                                          LinearLayout.LayoutParams.WRAP_CONTENT );
            lp.bottomMargin = dp( 8 );
            recent_list.addView( row, lp );
        }
    }

    /* 'start /unix "/data/.../drive_c/x/y.exe"' -> 'C:\x\y.exe' */
    private String recentPath( String cmdline )
    {
        Matcher m = Pattern.compile( "\"([^\"]*)\"" ).matcher( cmdline );
        if (!m.find()) return cmdline;
        String path = m.group( 1 );
        String drive_c = new File( prefixDir(), "drive_c" ).getPath();
        if (path.startsWith( drive_c )) return "C:" + path.substring( drive_c.length() ).replace( '/', '\\' );
        return path;
    }

    // Shortcuts, stored per runtime as "label\tcmdline" lines like the recent list

    private String shortcutKey()
    {
        return KEY_SHORTCUTS + "." + runtime;
    }

    private List<String[]> loadShortcuts()
    {
        String s = getSharedPreferences( PREFS, MODE_PRIVATE ).getString( shortcutKey(), "" );
        List<String[]> list = new ArrayList<String[]>();
        if (s.length() == 0) return list;
        for (String line : s.split( "\n" ))
        {
            String[] parts = line.split( "\t", 2 );
            if (parts.length == 2) list.add( parts );
        }
        return list;
    }

    private void saveShortcuts( List<String[]> list )
    {
        StringBuilder sb = new StringBuilder();
        for (String[] e : list)
        {
            if (sb.length() > 0) sb.append( '\n' );
            sb.append( e[0] ).append( '\t' ).append( e[1] );
        }
        getSharedPreferences( PREFS, MODE_PRIVATE ).edit().putString( shortcutKey(), sb.toString() ).apply();
    }

    private void addShortcut( String label, String cmdline )
    {
        List<String[]> list = loadShortcuts();
        for (String[] e : list)
        {
            if (e[1].equals( cmdline ))
            {
                Toast.makeText( this, e[0] + " is already a shortcut", Toast.LENGTH_SHORT ).show();
                return;
            }
        }
        list.add( new String[]{ label.replace( '\t', ' ' ).replace( '\n', ' ' ), cmdline } );
        saveShortcuts( list );
        refreshLibrary();
        Toast.makeText( this, "Added " + label, Toast.LENGTH_SHORT ).show();
    }

    private static final Pattern APPLAUNCH = Pattern.compile( "-applaunch (\\d+)" );

    private static String steamAppId( String cmdline )
    {
        Matcher m = APPLAUNCH.matcher( cmdline );
        return m.find() ? m.group( 1 ) : null;
    }

    /* Steam's cached artwork for a game, directly in its directory or in one of
     * the hashed subdirectories newer clients use */
    private File steamArt( String appid, String... names )
    {
        if (appid == null) return null;
        File dir = new File( prefixDir(), "drive_c/Program Files (x86)/Steam/appcache/librarycache/" + appid );
        File[] subdirs = dir.listFiles();
        for (String name : names)
        {
            File f = new File( dir, name );
            if (f.isFile()) return f;
            if (subdirs == null) continue;
            for (File sub : subdirs)
                if (sub.isDirectory() && (f = new File( sub, name )).isFile()) return f;
        }
        return null;
    }

    /* the library: the shortcuts first, in their order, then the installed
     * Steam games that aren't shortcuts */
    private void refreshLibrary()
    {
        library_grid.removeAllViews();
        hero_box.removeAllViews();
        final List<String[]> shortcuts = loadShortcuts();
        List<String[]> steam = new ArrayList<String[]>();
        findSteamGames( steam );

        List<String[]> items = new ArrayList<String[]>();  /* label, cmdline, shortcut index */
        for (int i = 0; i < shortcuts.size(); i++)
            items.add( new String[]{ shortcuts.get( i )[0], shortcuts.get( i )[1], String.valueOf( i ) } );
        for (String[] g : steam)
        {
            boolean dup = false;
            for (String[] e : shortcuts) if (e[1].equals( g[1] )) dup = true;
            if (!dup) items.add( new String[]{ g[0], g[1], "-1" } );
        }

        if (items.isEmpty())
        {
            library_grid.addView( Ui.text( this, "Install games with Steam (Proton runtime) or add "
                                           + "programs with + Add to start them from here.", 14,
                                           Ui.TEXT_SECONDARY, false ));
            return;
        }

        String last = getSharedPreferences( PREFS, MODE_PRIVATE ).getString( KEY_LAST_PLAYED + "." + runtime, "" );
        for (String[] item : items)
            if (item[1].equals( last ) && steamArt( steamAppId( item[1] ), "library_hero.jpg" ) != null)
                hero_box.addView( hero( item ));

        for (String[] item : items) library_grid.addView( gameCard( item ));
    }

    private void libraryMenu( final String[] item )
    {
        int index = Integer.parseInt( item[2] );
        if (index >= 0)
        {
            shortcutMenu( index, new String[]{ item[0], item[1] } );
            return;
        }
        new AlertDialog.Builder( this )
            .setTitle( item[0] )
            .setItems( new String[]{ "Add to home screen", "Pin to the top of the library" },
                       new DialogInterface.OnClickListener() {
                public void onClick( DialogInterface d, int which )
                {
                    if (which == 0) pinShortcut( new String[]{ item[0], item[1] } );
                    else addShortcut( item[0], item[1] );
                }} )
            .show();
    }

    private View gameCard( final String[] item )
    {
        String appid = steamAppId( item[1] );
        LinearLayout card = new LinearLayout( this );
        card.setOrientation( LinearLayout.VERTICAL );
        Ui.card( card, Ui.SURFACE, Ui.OUTLINE, 14 );

        File art = steamArt( appid, "header.jpg", "library_header.jpg", "capsule_616x353.jpg" );
        FrameLayout top = new FrameLayout( this );
        Ui.AspectImage image = new Ui.AspectImage( this, 460f / 215f );
        image.setBackgroundColor( Ui.SURFACE_ALT );
        top.addView( image );
        if (art != null) Ui.load( image, art, dp( 320 ));
        else
        {
            /* no artwork: the initial on a blue gradient */
            image.setBackground( new GradientDrawable( GradientDrawable.Orientation.TL_BR,
                                                       new int[]{ 0xFF1B4F82, 0xFF1C1C2A } ));
            TextView initial = Ui.text( this, item[0].substring( 0, 1 ).toUpperCase(), 44, Ui.TEXT, true );
            initial.setGravity( Gravity.CENTER );
            top.addView( initial, new FrameLayout.LayoutParams( FrameLayout.LayoutParams.MATCH_PARENT,
                                                                FrameLayout.LayoutParams.MATCH_PARENT ));
        }
        card.addView( top );

        LinearLayout text = new LinearLayout( this );
        text.setOrientation( LinearLayout.VERTICAL );
        text.setPadding( dp( 14 ), dp( 10 ), dp( 14 ), dp( 12 ));
        TextView title = Ui.text( this, item[0], 15, Ui.TEXT, true );
        title.setSingleLine( true );
        title.setEllipsize( TextUtils.TruncateAt.END );
        text.addView( title );
        text.addView( Ui.text( this, appid != null ? "Steam" : "Program", 12, Ui.TEXT_SECONDARY, false ));
        card.addView( text );

        card.setOnClickListener( new View.OnClickListener() {
            public void onClick( View v ) { runWine( item[0], item[1], false ); }} );
        card.setOnLongClickListener( new View.OnLongClickListener() {
            public boolean onLongClick( View v ) { libraryMenu( item ); return true; }} );
        return card;
    }

    /* the last played game, large, with its hero artwork and logo */
    private View hero( final String[] item )
    {
        String appid = steamAppId( item[1] );
        FrameLayout hero = new FrameLayout( this );
        Ui.card( hero, Ui.SURFACE, Ui.OUTLINE, 18 );

        Ui.AspectImage image = new Ui.AspectImage( this, 3.4f );
        hero.addView( image );
        Ui.load( image, steamArt( appid, "library_hero.jpg" ), dp( 900 ));

        View shade = new View( this );
        shade.setBackground( new GradientDrawable( GradientDrawable.Orientation.LEFT_RIGHT,
                                                   new int[]{ 0xE012121B, 0x6012121B, 0x0012121B } ));
        hero.addView( shade, new FrameLayout.LayoutParams( FrameLayout.LayoutParams.MATCH_PARENT,
                                                           FrameLayout.LayoutParams.MATCH_PARENT ));

        LinearLayout info = new LinearLayout( this );
        info.setOrientation( LinearLayout.VERTICAL );
        info.setGravity( Gravity.BOTTOM );
        info.setPadding( dp( 24 ), dp( 20 ), dp( 24 ), dp( 20 ));
        File logo = steamArt( appid, "logo.png" );
        if (logo != null)
        {
            ImageView logo_view = new ImageView( this );
            logo_view.setScaleType( ImageView.ScaleType.FIT_START );
            Ui.load( logo_view, logo, dp( 280 ));
            info.addView( logo_view, new LinearLayout.LayoutParams( dp( 280 ), dp( 90 )));
        }
        else info.addView( Ui.text( this, item[0], 26, Ui.TEXT, true ));
        TextView label = Ui.text( this, "CONTINUE PLAYING", 12, Ui.TEXT_SECONDARY, true );
        label.setLetterSpacing( 0.08f );
        label.setPadding( 0, dp( 10 ), 0, dp( 10 ));
        info.addView( label );
        LinearLayout buttons = new LinearLayout( this );
        buttons.addView( Ui.pill( this, "▶  Play", true, new View.OnClickListener() {
            public void onClick( View v ) { runWine( item[0], item[1], false ); }} ));
        info.addView( buttons );
        hero.addView( info, new FrameLayout.LayoutParams( FrameLayout.LayoutParams.MATCH_PARENT,
                                                          FrameLayout.LayoutParams.MATCH_PARENT ));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams( LinearLayout.LayoutParams.MATCH_PARENT,
                                                                      LinearLayout.LayoutParams.WRAP_CONTENT );
        lp.topMargin = dp( 24 );
        hero.setLayoutParams( lp );
        return hero;
    }

    private void shortcutMenu( final int index, final String[] entry )
    {
        new AlertDialog.Builder( this )
            .setTitle( entry[0] )
            .setItems( new String[]{ "Rename", "Add to home screen", "Move up", "Remove" },
                       new DialogInterface.OnClickListener() {
                public void onClick( DialogInterface d, int which )
                {
                    List<String[]> list = loadShortcuts();
                    if (index >= list.size()) return;
                    switch (which)
                    {
                    case 0: renameShortcut( index, entry ); return;
                    case 1: pinShortcut( entry ); return;
                    case 2:
                        if (index == 0) return;
                        list.add( index - 1, list.remove( index ));
                        break;
                    case 3:
                        list.remove( index );
                        break;
                    }
                    saveShortcuts( list );
                    refreshLibrary();
                }} )
            .show();
    }

    private void renameShortcut( final int index, final String[] entry )
    {
        final EditText edit = new EditText( this );
        edit.setSingleLine( true );
        edit.setText( entry[0] );
        edit.selectAll();
        new AlertDialog.Builder( this )
            .setTitle( "Rename shortcut" )
            .setView( edit )
            .setPositiveButton( android.R.string.ok, new DialogInterface.OnClickListener() {
                public void onClick( DialogInterface d, int which )
                {
                    String label = edit.getText().toString().trim().replace( '\t', ' ' );
                    List<String[]> list = loadShortcuts();
                    if (label.length() == 0 || index >= list.size()) return;
                    list.get( index )[0] = label;
                    saveShortcuts( list );
                    refreshLibrary();
                }} )
            .setNegativeButton( android.R.string.cancel, null )
            .show();
    }

    /* the home screen shortcut opens ShortcutActivity, which hands the program
     * to WineActivity; see there why it doesn't open this launcher */
    private void pinShortcut( String[] entry )
    {
        ShortcutManager sm = getSystemService( ShortcutManager.class );
        if (sm == null || !sm.isRequestPinShortcutSupported())
        {
            Toast.makeText( this, "The home screen doesn't support shortcuts", Toast.LENGTH_SHORT ).show();
            return;
        }
        Intent intent = new Intent( ShortcutActivity.ACTION_RUN );
        intent.setClass( this, ShortcutActivity.class );
        intent.putExtra( WineActivity.EXTRA_CMDLINE, entry[1] );
        intent.putExtra( WineActivity.EXTRA_RUNTIME, runtime );
        ShortcutInfo info = new ShortcutInfo.Builder( this, runtime + ":" + entry[1] )
            .setShortLabel( entry[0] )
            .setIcon( Icon.createWithResource( this, R.drawable.wine ))
            .setIntent( intent )
            .build();
        sm.requestPinShortcut( info, null );
    }

    /* "Celeste.exe" -> "Celeste" */
    private static String programLabel( String name )
    {
        String lower = name.toLowerCase();
        for (String ext : new String[]{ ".exe", ".msi", ".bat", ".lnk" })
            if (lower.endsWith( ext ) && name.length() > ext.length())
                return name.substring( 0, name.length() - ext.length() );
        return name;
    }

    private File prefixDir()
    {
        return new File( new File( getFilesDir(), runtime ), "prefix" );
    }

    /* offer what's installed in this runtime's C: drive: Steam games, Start
     * menu entries (made by installers) and the recent programs */
    private void addShortcutDialog()
    {
        final List<String[]> found = new ArrayList<String[]>();
        findSteamGames( found );
        findStartMenu( found );
        for (String entry : loadRecent())
        {
            String[] parts = entry.split( "\t", 2 );
            if (parts.length == 2) found.add( new String[]{ programLabel( parts[0] ), parts[1], "Recent" } );
        }

        final String[] items = new String[found.size() + 1];
        items[0] = "Choose a file…";
        for (int i = 0; i < found.size(); i++)
            items[i + 1] = found.get( i )[0] + "  (" + found.get( i )[2] + ")";

        new AlertDialog.Builder( this )
            .setTitle( "Add a shortcut for " + runtimeLabel( runtime ))
            .setItems( items, new DialogInterface.OnClickListener() {
                public void onClick( DialogInterface d, int which )
                {
                    if (which == 0)
                    {
                        Intent intent = new Intent( Intent.ACTION_OPEN_DOCUMENT );
                        intent.addCategory( Intent.CATEGORY_OPENABLE );
                        intent.setType( "*/*" );
                        startActivityForResult( intent, REQUEST_PICK_SHORTCUT );
                        return;
                    }
                    String[] e = found.get( which - 1 );
                    addShortcut( e[0], e[1] );
                }} )
            .setNegativeButton( android.R.string.cancel, null )
            .show();
    }

    private static final Pattern ACF_APPID = Pattern.compile( "\"appid\"\\s+\"(\\d+)\"" );
    private static final Pattern ACF_NAME = Pattern.compile( "\"name\"\\s+\"([^\"]*)\"" );
    private static final String STEAMWORKS_REDIST = "228980";

    /* Steam games are started through steam.exe, which passes them to the
     * running Steam (or starts it), so the Steam overlay and DRM keep working */
    private void findSteamGames( List<String[]> found )
    {
        File steam = new File( prefixDir(), "drive_c/Program Files (x86)/Steam" );
        File[] acfs = new File( steam, "steamapps" ).listFiles();
        if (acfs == null) return;
        TreeMap<String,String> games = new TreeMap<String,String>( String.CASE_INSENSITIVE_ORDER );
        for (File acf : acfs)
        {
            if (!acf.getName().startsWith( "appmanifest_" ) || !acf.getName().endsWith( ".acf" )) continue;
            String text = readText( acf );
            Matcher id = ACF_APPID.matcher( text ), name = ACF_NAME.matcher( text );
            if (!id.find() || !name.find() || id.group( 1 ).equals( STEAMWORKS_REDIST )) continue;
            games.put( name.group( 1 ), id.group( 1 ));
        }
        String exe = new File( steam, "steam.exe" ).getPath();
        for (java.util.Map.Entry<String,String> g : games.entrySet())
            found.add( new String[]{ g.getKey(), "start /unix \"" + exe + "\" -applaunch " + g.getValue(), "Steam" } );
    }

    private void findStartMenu( List<String[]> found )
    {
        TreeMap<String,File> links = new TreeMap<String,File>( String.CASE_INSENSITIVE_ORDER );
        File drive_c = new File( prefixDir(), "drive_c" );
        findLinks( new File( drive_c, "ProgramData/Microsoft/Windows/Start Menu/Programs" ), links, 0 );
        File[] users = new File( drive_c, "users" ).listFiles();
        if (users != null)
            for (File user : users)
                findLinks( new File( user, "AppData/Roaming/Microsoft/Windows/Start Menu/Programs" ), links, 0 );
        for (java.util.Map.Entry<String,File> l : links.entrySet())
            found.add( new String[]{ l.getKey(), unixCmdline( l.getValue() ), "Start menu" } );
    }

    private static void findLinks( File dir, TreeMap<String,File> links, int depth )
    {
        File[] files = dir.listFiles();
        if (files == null || depth > 3) return;
        for (File f : files)
        {
            String name = f.getName();
            if (f.isDirectory())
            {
                if (!name.equals( "StartUp" ) && !name.equals( "Administrative Tools" ))
                    findLinks( f, links, depth + 1 );
            }
            else if (name.toLowerCase().endsWith( ".lnk" ) && !name.toLowerCase().startsWith( "uninstall" ))
                links.put( programLabel( name ), f );
        }
    }

    private static String readText( File file )
    {
        try
        {
            InputStream in = new FileInputStream( file );
            byte[] data = new byte[(int)Math.min( file.length(), 1 << 20 )];
            int len = 0, n;
            while (len < data.length && (n = in.read( data, len, data.length - len )) > 0) len += n;
            in.close();
            return new String( data, 0, len, "UTF-8" );
        }
        catch (IOException e) { return ""; }
    }

    // Storage access, used for drive D:

    private void requestStorage()
    {
        if (Build.VERSION.SDK_INT < 23) return;
        if (checkSelfPermission( Manifest.permission.WRITE_EXTERNAL_STORAGE ) == PackageManager.PERMISSION_GRANTED)
            return;
        requestPermissions( new String[]{ Manifest.permission.READ_EXTERNAL_STORAGE,
                                          Manifest.permission.WRITE_EXTERNAL_STORAGE }, REQUEST_PERMS );
    }
}
