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
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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

    /* runtimes the APK can carry: asset directory and name shown */
    private static final String[][] RUNTIMES = {
        { WineActivity.RUNTIME_WINE, "Wine 11.0" },
        { WineActivity.RUNTIME_PROTON, "Proton 11.0" },
    };

    private LinearLayout recent_list;
    private LinearLayout shortcut_list;
    private RadioGroup runtime_group;
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
        refreshShortcuts();
        refreshRecent();
    }

    private void buildUi()
    {
        int pad = dp( 16 );
        LinearLayout root = new LinearLayout( this );
        root.setOrientation( LinearLayout.VERTICAL );
        root.setPadding( pad, pad, pad, pad );

        TextView title = new TextView( this );
        title.setText( "WineDroid" );
        title.setTextSize( TypedValue.COMPLEX_UNIT_SP, 28 );
        root.addView( title );

        TextView sub = new TextView( this );
        sub.setText( "Windows programs running natively on x86_64. The first start of each "
                     + "runtime unpacks it and sets up its Windows environment, which takes a "
                     + "few minutes." );
        sub.setPadding( 0, dp( 4 ), 0, dp( 16 ));
        root.addView( sub );

        TextView runtime_title = new TextView( this );
        runtime_title.setText( "Runtime" );
        runtime_title.setTextSize( TypedValue.COMPLEX_UNIT_SP, 20 );
        root.addView( runtime_title );

        /* each runtime has its own C: drive, so programs installed with one
         * are not visible to the other */
        runtime_group = new RadioGroup( this );
        runtime_group.setOrientation( RadioGroup.HORIZONTAL );
        for (int i = 0; i < RUNTIMES.length; i++)
        {
            if (!hasRuntime( RUNTIMES[i][0] )) continue;
            RadioButton rb = new RadioButton( this );
            rb.setId( i + 1 );
            rb.setText( RUNTIMES[i][1] );
            rb.setPadding( 0, 0, dp( 16 ), 0 );
            runtime_group.addView( rb );
            if (RUNTIMES[i][0].equals( runtime )) rb.setChecked( true );
        }
        runtime_group.setOnCheckedChangeListener( new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged( RadioGroup group, int id ) { selectRuntime( RUNTIMES[id - 1][0] ); }} );
        runtime_group.setPadding( 0, 0, 0, dp( 16 ));
        root.addView( runtime_group );

        TextView shortcut_title = new TextView( this );
        shortcut_title.setText( "Shortcuts" );
        shortcut_title.setTextSize( TypedValue.COMPLEX_UNIT_SP, 20 );
        root.addView( shortcut_title );

        shortcut_list = new LinearLayout( this );
        shortcut_list.setOrientation( LinearLayout.VERTICAL );
        root.addView( shortcut_list );

        root.addView( button( "+ Add shortcut…", new View.OnClickListener() {
            public void onClick( View v ) { addShortcutDialog(); }} ));

        TextView programs_title = new TextView( this );
        programs_title.setText( "Programs" );
        programs_title.setTextSize( TypedValue.COMPLEX_UNIT_SP, 20 );
        programs_title.setPadding( 0, dp( 24 ), 0, dp( 8 ));
        root.addView( programs_title );

        root.addView( button( "Run a Windows program (.exe / .msi)…", new View.OnClickListener() {
            public void onClick( View v ) { pickProgram(); }} ));
        root.addView( button( "Wine desktop (file manager)", runner( "Wine desktop", "explorer.exe" )));
        root.addView( button( "Command prompt", runner( "Command prompt", "wineconsole.exe" )));
        root.addView( button( "Wine settings (winecfg)", runner( "Wine settings", "winecfg.exe" )));
        root.addView( button( "Task manager", runner( "Task manager", "taskmgr.exe" )));
        root.addView( button( "Stop Wine", new View.OnClickListener() {
            public void onClick( View v ) { stopWine(); }} ));

        TextView recent_title = new TextView( this );
        recent_title.setText( "Recent programs" );
        recent_title.setTextSize( TypedValue.COMPLEX_UNIT_SP, 20 );
        recent_title.setPadding( 0, dp( 24 ), 0, dp( 8 ));
        root.addView( recent_title );

        recent_list = new LinearLayout( this );
        recent_list.setOrientation( LinearLayout.VERTICAL );
        root.addView( recent_list );

        TextView hint = new TextView( this );
        hint.setText( "Tips: drive D: is your phone storage and drive Z: is the whole file "
                      + "system. Tap the ⌨ button in Wine to show the on-screen keyboard. "
                      + "Touches are left clicks; a USB or Bluetooth mouse and keyboard work too." );
        hint.setPadding( 0, dp( 24 ), 0, 0 );
        root.addView( hint );

        ScrollView scroll = new ScrollView( this );
        scroll.addView( root );
        setContentView( scroll );
    }

    private Button button( String label, View.OnClickListener listener )
    {
        Button b = new Button( this );
        b.setText( label );
        b.setAllCaps( false );
        b.setOnClickListener( listener );
        return b;
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
        refreshShortcuts();
        refreshRecent();
    }

    private void saveRuntime( String name )
    {
        runtime = name;
        getSharedPreferences( PREFS, MODE_PRIVATE ).edit().putString( KEY_RUNTIME, name ).commit();
    }

    private void checkRuntime( String name )
    {
        for (int i = 0; i < RUNTIMES.length; i++)
            if (RUNTIMES[i][0].equals( name ) && runtime_group.findViewById( i + 1 ) != null)
                runtime_group.check( i + 1 );
    }

    private int dp( int v )
    {
        return (int)TypedValue.applyDimension( TypedValue.COMPLEX_UNIT_DIP, v,
                                               getResources().getDisplayMetrics() );
    }

    // Running programs

    private void runWine( String label, String cmdline, boolean remember )
    {
        if (remember) addRecent( label, cmdline );
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
            TextView none = new TextView( this );
            none.setText( "Nothing yet." );
            recent_list.addView( none );
            return;
        }
        for (final String entry : list)
        {
            final String[] parts = entry.split( "\t", 2 );
            if (parts.length != 2) continue;
            Button b = button( parts[0], runner( parts[0], parts[1] ));
            b.setOnLongClickListener( new View.OnLongClickListener() {
                public boolean onLongClick( View v )
                {
                    new AlertDialog.Builder( LauncherActivity.this )
                        .setTitle( parts[0] )
                        .setItems( new String[]{ "Add to shortcuts", "Remove from recent" },
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
            recent_list.addView( b );
        }
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
        refreshShortcuts();
        Toast.makeText( this, "Added " + label, Toast.LENGTH_SHORT ).show();
    }

    private void refreshShortcuts()
    {
        shortcut_list.removeAllViews();
        final List<String[]> list = loadShortcuts();
        if (list.isEmpty())
        {
            TextView none = new TextView( this );
            none.setText( "Add programs and Steam games here to start them with one tap." );
            shortcut_list.addView( none );
            return;
        }
        for (int i = 0; i < list.size(); i++)
        {
            final int index = i;
            final String[] e = list.get( i );
            Button b = button( e[0], new View.OnClickListener() {
                public void onClick( View v ) { runWine( e[0], e[1], false ); }} );
            b.setOnLongClickListener( new View.OnLongClickListener() {
                public boolean onLongClick( View v ) { shortcutMenu( index, e ); return true; }} );
            shortcut_list.addView( b );
        }
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
                    refreshShortcuts();
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
                    refreshShortcuts();
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
