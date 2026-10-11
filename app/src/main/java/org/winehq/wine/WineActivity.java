/*
 * WineActivity class
 *
 * Copyright 2013-2017 Alexandre Julliard
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
 */

package org.winehq.wine;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.text.InputType;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.media.ImageReader;
import android.media.Image;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;

public class WineActivity extends Activity
{
    private native String wine_init( String[] cmdline, String[] env );
    public native void wine_desktop_changed( int width, int height );
    public native void wine_config_changed( int dpi );
    public native void wine_surface_changed( int hwnd, Surface surface, boolean opengl );
    public native boolean wine_motion_event( int hwnd, int action, int x, int y, int state,
                                             int vscroll, int hscroll );
    public native boolean wine_relative_motion_event( int action, int dx, int dy, int state,
                                                      int vscroll, int hscroll );
    public native boolean wine_keyboard_event( int hwnd, int action, int keycode, int state );

    private final String LOGTAG = "wine";
    private ProgressDialog progress_dialog;

    protected WineWindow desktop_window;
    protected WineWindow message_window;
    private PointerIcon current_cursor;
    private boolean cursor_hidden;

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate( savedInstanceState );

        requestWindowFeature( android.view.Window.FEATURE_NO_TITLE );

        final String cmdline = getIntent().getStringExtra( EXTRA_CMDLINE );
        if (wine_env != null)
        {
            /* Wine is already running in this process (activity recreated) */
            if (cmdline != null) startWineProcess( cmdline );
            return;
        }
        wine_runtime = getIntent().getStringExtra( EXTRA_RUNTIME );
        if (wine_runtime == null) wine_runtime = RUNTIME_WINE;
        new Thread( new Runnable() { public void run() { loadWine( cmdline ); }} ).start();
    }

    /* Windows command line to run, passed by the launcher */
    public static final String EXTRA_CMDLINE = "org.winehq.wine.CMDLINE";
    /* launcher setting: factor applied to mouse motion while the pointer is captured */
    public static final String PREFS_LAUNCHER = "launcher";
    public static final String PREF_MOUSE_SPEED = "mouse_speed";
    /* Wine build to run, the name of its directory in the assets */
    public static final String EXTRA_RUNTIME = "org.winehq.wine.RUNTIME";
    public static final String RUNTIME_WINE = "wine";
    public static final String RUNTIME_PROTON = "proton";

    /* environment of the running Wine instance, null until Wine is started */
    private static HashMap<String,String> wine_env;
    /* runtime the process is using; only one can be loaded per process */
    private static String wine_runtime;

    /* runtime of the Wine instance running in this process, or null */
    public static String getRunningRuntime()
    {
        return wine_env != null ? wine_runtime : null;
    }

    /* Wine cannot be restarted inside the same process, and a new activity
     * cannot take over the desktop of the running one, so stopping Wine ends
     * the process; the Windows programs started from the launcher run as
     * separate processes, so ask the wineserver to end them first */
    public static void stopWine()
    {
        if (wine_env != null)
        {
            String server = wine_env.get( "PATH" ).split( ":" )[0] + "/wineserver";
            ProcessBuilder pb = new ProcessBuilder( server, "-k" );
            pb.environment().putAll( wine_env );
            try
            {
                java.lang.Process p = pb.start();
                p.waitFor( 3, java.util.concurrent.TimeUnit.SECONDS );
            }
            catch (Exception e)
            {
                Log.e( "wine", "failed to stop the wineserver: " + e );
            }
        }
        android.os.Process.killProcess( android.os.Process.myPid() );
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();
        /* closing the Wine window ends Wine; otherwise it would keep running
         * without a window and the next program would open on a blank one */
        if (isFinishing() && wine_env != null) stopWine();
    }

    /* each runtime is extracted to its own directory with its own prefix,
     * since a prefix set up by one Wine build is not meant for another */
    private File getRuntimeDir()
    {
        return new File( getFilesDir(), wine_runtime );
    }

    @Override
    protected void onNewIntent( Intent intent )
    {
        super.onNewIntent( intent );
        String cmdline = intent.getStringExtra( EXTRA_CMDLINE );
        if (cmdline != null)
        {
            String runtime = intent.getStringExtra( EXTRA_RUNTIME );
            if (runtime != null && !runtime.equals( wine_runtime ))
                Log.w( LOGTAG, "running " + cmdline + " with " + wine_runtime + ", not " + runtime );
            startWineProcess( cmdline );
        }
    }

    /* Wine can only be initialized once per process, so further programs are
     * started as separate Wine processes; their windows are still displayed
     * by the desktop running in this activity. */
    private void startWineProcess( String cmdline )
    {
        if (wine_env == null)
        {
            Log.e( LOGTAG, "Wine is not running yet, ignoring " + cmdline );
            return;
        }
        ArrayList<String> cmd = new ArrayList<String>();
        cmd.add( wine_env.get( "WINELOADER" ));
        cmd.add( "c:\\windows\\system32\\cmd.exe" );
        cmd.add( "/c" );
        cmd.addAll( splitCmdline( cmdline ));
        ProcessBuilder pb = new ProcessBuilder( cmd );
        pb.environment().putAll( wine_env );
        /* open on the desktop created in runWine(), not on a new one */
        pb.environment().put( "WINEDROID_DESKTOP", DESKTOP_NAME );
        pb.redirectErrorStream( true );
        pb.redirectOutput( new File( getFilesDir(), "process.log" ));
        try
        {
            pb.start();
            Log.i( LOGTAG, "started " + cmdline );
        }
        catch (IOException e)
        {
            Log.e( LOGTAG, "failed to start " + cmdline + ": " + e );
        }
    }

    /* map D: to the shared storage so it is easy to find in Windows programs */
    private void createStorageDrive( File prefix )
    {
        File dosdevices = new File( prefix, "dosdevices" );
        File d = new File( dosdevices, "d:" );
        if (!dosdevices.isDirectory() || d.exists()) return;
        try
        {
            android.system.Os.symlink( android.os.Environment.getExternalStorageDirectory().getPath(),
                                       d.getPath() );
        }
        catch (Exception e)
        {
            Log.w( LOGTAG, "could not create drive D: " + e );
        }
    }

    /* copy a runtime file into the prefix unless it's already there */
    private void installFile( File src, File dest )
    {
        if (dest.length() == src.length() && dest.lastModified() >= src.lastModified()) return;
        Log.i( LOGTAG, "installing " + dest );
        try (FileInputStream in = new FileInputStream( src );
             FileOutputStream out = new FileOutputStream( dest ))
        {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read( buffer )) > 0) out.write( buffer, 0, n );
        }
        catch (IOException e)
        {
            Log.e( LOGTAG, "could not install " + dest + ": " + e );
        }
    }

    /* copy DXVK (Direct3D 8-11 on Vulkan) from the runtime into the prefix, where Wine
     * prefers it over its own Direct3D (WINEDLLOVERRIDES); Wine's prefix setup leaves
     * DLLs that aren't its own alone. Returns whether the runtime has DXVK. */
    private boolean installDxvk( File prefix )
    {
        File dxvk = new File( getRuntimeDir(), "share/dxvk" );
        String[][] dirs = { { "x64", "system32" }, { "x32", "syswow64" } };
        boolean found = false;

        for (String[] dir : dirs)
        {
            File[] dlls = new File( dxvk, dir[0] ).listFiles();
            if (dlls == null) continue;
            File dest_dir = new File( prefix, "drive_c/windows/" + dir[1] );
            dest_dir.mkdirs();
            for (File src : dlls)
            {
                found = true;
                installFile( src, new File( dest_dir, src.getName() ));
            }
        }
        return found;
    }

    /* Proton's wine.inf registers its fonts in HKLM as plain file names (tahoma.ttf),
     * which DirectWrite looks for in C:\windows\Fonts; Proton's launch script copies
     * them there. Without them Chromium (Steam's web UI) can't find the Tahoma of the
     * system metrics, falls back to the system font and recurses until it crashes. */
    private void installFonts( File prefix )
    {
        File[] fonts = new File( getRuntimeDir(), "share/wine/fonts" ).listFiles();
        if (fonts == null) return;
        File dest_dir = new File( prefix, "drive_c/windows/Fonts" );
        dest_dir.mkdirs();
        for (File src : fonts)
            if (src.getName().endsWith( ".ttf" )) installFile( src, new File( dest_dir, src.getName() ));
    }

    @TargetApi(21)
    @SuppressWarnings("deprecation")
    private String[] get_supported_abis()
    {
        if (Build.VERSION.SDK_INT >= 21) return Build.SUPPORTED_ABIS;
        return new String[]{ Build.CPU_ABI };
    }

    private String get_wine_abi()
    {
        for (String abi : get_supported_abis())
        {
            File server = new File( getRuntimeDir(), abi + "/bin/wineserver" );
            if (server.canExecute()) return abi;
        }
        Log.e( LOGTAG, "could not find a supported ABI" );
        return null;
    }

    private String get_so_dir( String abi )
    {
        if (abi.equals( "x86" )) return "/i386-unix";
        if (abi.equals( "x86_64" )) return "/x86_64-unix";
        if (abi.equals( "armeabi-v7a" )) return "/arm-unix";
        if (abi.equals( "arm64-v8a" )) return "/aarch64-unix";
        return "";
    }

    /* Kill what is left of an earlier Wine session: Android's force stop can miss a
     * Windows process whose main thread has exited while others still run (a Steam
     * controller thread kept a CPU core busy for an hour that way). Wine isn't running
     * yet, so no other process of this app should exist. */
    private void killStaleProcesses()
    {
        int my_pid = android.os.Process.myPid();
        int my_uid = android.os.Process.myUid();
        File[] procs = new File( "/proc" ).listFiles();
        if (procs == null) return;
        for (File dir : procs)
        {
            int pid;
            try { pid = Integer.parseInt( dir.getName() ); }
            catch (NumberFormatException e) { continue; }
            if (pid == my_pid) continue;
            try (BufferedReader reader = new BufferedReader( new FileReader( new File( dir, "status" ))))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    if (!line.startsWith( "Uid:" )) continue;
                    if (Integer.parseInt( line.split( "\\s+" )[1] ) == my_uid)
                    {
                        Log.w( LOGTAG, "killing stale process " + pid );
                        android.os.Process.killProcess( pid );
                    }
                    break;
                }
            }
            catch (IOException | NumberFormatException e) {}
        }
    }

    private void loadWine( String cmdline )
    {
        copyAssetFiles();
        killStaleProcesses();

        String wine_abi = get_wine_abi();
        File bindir = new File( getRuntimeDir(), wine_abi + "/bin" );
        File libdir = new File( getRuntimeDir(), wine_abi + "/lib" );
        File dlldir = new File( libdir, "wine" );
        File prefix = new File( getRuntimeDir(), "prefix" );
        /* Wine 11 installs the loader next to ntdll.so, not in bin/ */
        File loader = new File( dlldir.toString() + get_so_dir(wine_abi) + "/wine" );
        String locale = Locale.getDefault().getLanguage() + "_" +
            Locale.getDefault().getCountry() + ".UTF-8";

        HashMap<String,String> env = new HashMap<String,String>();
        env.put( "WINELOADER", loader.toString() );
        env.put( "WINEPREFIX", prefix.toString() );
        env.put( "WINEDLLPATH", dlldir.toString() );
        env.put( "LD_LIBRARY_PATH", libdir.toString() + ":" + getApplicationInfo().nativeLibraryDir );
        env.put( "LC_ALL", locale );
        env.put( "LANG", locale );
        env.put( "PATH", bindir.toString() + ":" + System.getenv( "PATH" ));
        env.put( "WINEDROID_ROUTES", new File( getFilesDir(), "net/routes" ).toString() );
        env.put( "WINEDROID_DNS", new File( getFilesDir(), "net/dns" ).toString() );
        watchNetwork( getApplicationContext() );
        if (RUNTIME_PROTON.equals( wine_runtime ))
        {
            /* default of Proton's launch script; esync and fsync stay off
             * since Android has no POSIX shared memory */
            env.put( "WINE_LARGE_ADDRESS_AWARE", "1" );
            /* Proton swaps steamclient(64).dll for a bridge to the native Linux Steam client,
             * which Android doesn't have; the Windows Steam client needs its own, or Steam
             * says "There was a problem with your Steam installation" */
            env.put( "PROTON_DISABLE_LSTEAMCLIENT", "1" );
        }

        if (cmdline == null)
        {
            if (new File( prefix, "drive_c/winestart.cmd" ).exists()) cmdline = "c:\\winestart.cmd";
            else cmdline = "c:\\windows\\system32\\wineconsole.exe";
        }

        String winedebug = readFileString( new File( prefix, "winedebug" ));
        if (winedebug == null) winedebug = readFileString( new File( getFilesDir(), "winedebug" ));
        if (winedebug != null)
        {
            File log = new File( getFilesDir(), "log" );
            env.put( "WINEDEBUG", winedebug );
            env.put( "WINEDEBUGLOG", log.toString() );
            Log.i( LOGTAG, "logging to " + log.toString() );
            log.delete();
        }

        createProgressDialog( 0, "Setting up the Windows environment (" + wine_runtime + ")..." );

        System.load( dlldir.toString() + get_so_dir(wine_abi) + "/ntdll.so" );
        prefix.mkdirs();
        createStorageDrive( prefix );
        if (installDxvk( prefix )) env.put( "WINEDLLOVERRIDES", "d3d8,d3d9,d3d10core,d3d11,dxgi=n,b" );
        if (RUNTIME_PROTON.equals( wine_runtime )) installFonts( prefix );
        wine_env = env;

        runWine( cmdline, env );
    }

    /* "root" makes explorer use the Android screen as the display instead of
     * an 800x600 virtual desktop, which would hide the real screen size */
    private static final String DESKTOP_NAME = "root";

    private final void runWine( String cmdline, HashMap<String,String> environ )
    {
        String[] env = new String[environ.size() * 2];
        int j = 0;
        for (Map.Entry<String,String> entry : environ.entrySet())
        {
            env[j++] = entry.getKey();
            env[j++] = entry.getValue();
        }

        ArrayList<String> cmd = new ArrayList<String>();
        cmd.add( environ.get( "WINELOADER" ));
        cmd.add( "c:\\windows\\system32\\explorer.exe" );
        cmd.add( "/desktop=" + DESKTOP_NAME + ",,android" );
        /* explorer runs the rest of its command line, which Wine rebuilds from
         * argv; a single argument with spaces would be quoted as a whole */
        cmd.addAll( splitCmdline( cmdline ));

        String err = wine_init( cmd.toArray( new String[0] ), env );
        Log.e( LOGTAG, err );
    }

    /* split a Windows command line into arguments like CommandLineToArgvW */
    private static ArrayList<String> splitCmdline( String cmdline )
    {
        ArrayList<String> args = new ArrayList<String>();
        StringBuilder arg = new StringBuilder();
        boolean in_arg = false, in_quotes = false;
        int i = 0, len = cmdline.length();

        while (i < len)
        {
            char c = cmdline.charAt( i );
            if (!in_quotes && (c == ' ' || c == '\t'))
            {
                if (in_arg) args.add( arg.toString() );
                arg.setLength( 0 );
                in_arg = false;
                i++;
                continue;
            }
            in_arg = true;
            if (c == '\\')
            {
                int count = 0;
                while (i < len && cmdline.charAt( i ) == '\\') { count++; i++; }
                if (i < len && cmdline.charAt( i ) == '"')
                {
                    for (int k = 0; k < count / 2; k++) arg.append( '\\' );
                    if (count % 2 == 1) { arg.append( '"' ); i++; }
                }
                else for (int k = 0; k < count; k++) arg.append( '\\' );
            }
            else if (c == '"')
            {
                if (in_quotes && i + 1 < len && cmdline.charAt( i + 1 ) == '"')
                {
                    arg.append( '"' );
                    i++;
                }
                else in_quotes = !in_quotes;
                i++;
            }
            else
            {
                arg.append( c );
                i++;
            }
        }
        if (in_arg) args.add( arg.toString() );
        return args;
    }

    private void createProgressDialog( final int max, final String message )
    {
        runOnUiThread( new Runnable() { public void run() {
            if (progress_dialog != null) progress_dialog.dismiss();
            progress_dialog = new ProgressDialog( WineActivity.this );
            progress_dialog.setProgressStyle( max > 0 ? ProgressDialog.STYLE_HORIZONTAL
                                              : ProgressDialog.STYLE_SPINNER );
            progress_dialog.setTitle( "Wine" );
            progress_dialog.setMessage( message );
            progress_dialog.setCancelable( false );
            progress_dialog.setMax( max );
            progress_dialog.show();
        }});

    }

    private final boolean isFileWanted( String name )
    {
        if (name.equals( "files.sum" )) return true;
        if (name.startsWith( "share/" )) return true;
        for (String abi : get_supported_abis())
        {
            if (name.startsWith( abi + "/system/" )) return false;
            if (name.startsWith( abi + "/" )) return true;
        }
        if (name.startsWith( "x86/" )) return true;
        return false;
    }

    private final boolean isFileExecutable( String name )
    {
        return !name.equals( "files.sum" ) && !name.startsWith( "share/" );
    }

    private final HashMap<String,String> readMapFromInputStream( InputStream in )
    {
        HashMap<String,String> map = new HashMap<String,String>();
        String str;

        try
        {
            BufferedReader reader = new BufferedReader( new InputStreamReader( in, "UTF-8" ));
            while ((str = reader.readLine()) != null)
            {
                String entry[] = str.split( "\\s+", 2 );
                if (entry.length == 2 && isFileWanted( entry[1] )) map.put( entry[1], entry[0] );
            }
        }
        catch( IOException e ) { }
        return map;
    }

    private final HashMap<String,String> readMapFromDiskFile( String file )
    {
        try
        {
            return readMapFromInputStream( new FileInputStream( new File( getRuntimeDir(), file )));
        }
        catch( IOException e ) { return new HashMap<String,String>(); }
    }

    private final HashMap<String,String> readMapFromAssetFile( String file )
    {
        try
        {
            return readMapFromInputStream( getAssets().open( wine_runtime + "/" + file ) );
        }
        catch( IOException e ) { return new HashMap<String,String>(); }
    }

    // Network information

    /* Android denies apps /proc/net/route and keeps the DNS servers to
     * itself, so Wine's nsiproxy and dnsapi read them from files written here
     * (patches 0012 and 0013). Without a default route Windows programs
     * think they are offline. Both are re-read on every query, so rewriting
     * them when the network changes is enough. */
    private static void watchNetwork( final Context context )
    {
        final ConnectivityManager cm = (ConnectivityManager)context.getSystemService( Context.CONNECTIVITY_SERVICE );
        writeNetworkFiles( context, cm );
        cm.registerDefaultNetworkCallback( new ConnectivityManager.NetworkCallback()
        {
            @Override
            public void onAvailable( Network network ) { writeNetworkFiles( context, cm ); }
            @Override
            public void onLost( Network network ) { writeNetworkFiles( context, cm ); }
            @Override
            public void onLinkPropertiesChanged( Network network, LinkProperties lp ) { writeNetworkFiles( context, cm ); }
        });
    }

    /* an IPv4 address the way /proc/net/route prints it: the in_addr as a
     * host-order (little-endian) number */
    private static int procNetAddr( byte[] b )
    {
        return (b[0] & 0xff) | (b[1] & 0xff) << 8 | (b[2] & 0xff) << 16 | (b[3] & 0xff) << 24;
    }

    private static synchronized void writeNetworkFiles( Context context, ConnectivityManager cm )
    {
        StringBuilder routes = new StringBuilder( "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT\n" );
        LinkedHashSet<String> dns = new LinkedHashSet<String>();
        ArrayList<Network> networks = new ArrayList<Network>();
        Network active = cm.getActiveNetwork();

        /* the default network first, so its DNS servers come first */
        if (active != null) networks.add( active );
        for (Network net : cm.getAllNetworks()) if (!net.equals( active )) networks.add( net );

        for (Network net : networks)
        {
            LinkProperties lp = cm.getLinkProperties( net );
            if (lp == null) continue;
            for (InetAddress addr : lp.getDnsServers()) dns.add( addr.getHostAddress() );
            for (RouteInfo route : lp.getRoutes())
            {
                InetAddress dest = route.getDestination().getAddress();
                int len = route.getDestination().getPrefixLength();
                if (!(dest instanceof Inet4Address) || route.getInterface() == null) continue;
                int mask = len == 0 ? 0 : Integer.reverseBytes( -1 << (32 - len) );
                int gateway = route.hasGateway() ? procNetAddr( route.getGateway().getAddress() ) : 0;
                int flags = 0x1 /* RTF_UP */ | (route.hasGateway() ? 0x2 /* RTF_GATEWAY */ : 0);
                routes.append( String.format( Locale.ROOT, "%s\t%08X\t%08X\t%04X\t0\t0\t0\t%08X\t0\t0\t0\n",
                                              route.getInterface(), procNetAddr( dest.getAddress() ),
                                              gateway, flags, mask ));
            }
        }

        StringBuilder servers = new StringBuilder();
        for (String addr : dns) servers.append( addr ).append( '\n' );
        File dir = new File( context.getFilesDir(), "net" );
        dir.mkdirs();
        writeFileAtomic( new File( dir, "routes" ), routes.toString() );
        writeFileAtomic( new File( dir, "dns" ), servers.toString() );
    }

    /* Wine may read the file at any time, so never let it see half of one */
    private static void writeFileAtomic( File file, String contents )
    {
        File tmp = new File( file.getPath() + ".tmp" );
        try
        {
            FileOutputStream out = new FileOutputStream( tmp );
            out.write( contents.getBytes( "UTF-8" ));
            out.close();
            if (!tmp.renameTo( file )) Log.e( "wine", "failed to replace " + file );
        }
        catch (IOException e)
        {
            Log.e( "wine", "failed to write " + file + ": " + e );
        }
    }

    private final String readFileString( File file )
    {
        try
        {
            FileInputStream in = new FileInputStream( file );
            BufferedReader reader = new BufferedReader( new InputStreamReader( in, "UTF-8" ));
            return reader.readLine();
        }
        catch( IOException e ) { return null; }
    }

    private final void copyAssetFile( String src )
    {
        File dest = new File( getRuntimeDir(), src );
        try
        {
            Log.i( LOGTAG, "extracting " + dest );
            dest.getParentFile().mkdirs();
            dest.delete();
            if (dest.createNewFile())
            {
                InputStream in = getAssets().open( wine_runtime + "/" + src );
                FileOutputStream out = new FileOutputStream( dest );
                int read;
                byte[] buffer = new byte[65536];

                while ((read = in.read( buffer )) > 0) out.write( buffer, 0, read );
                out.close();
                if (isFileExecutable( src )) dest.setExecutable( true, true );
            }
            else
                Log.i( LOGTAG, "Failed to create file "  + dest );
        }
        catch( IOException e )
        {
            Log.i( LOGTAG, "Failed to copy asset file to " + dest );
            dest.delete();
        }
    }

    private final void deleteAssetFile( String src )
    {
        File dest = new File( getRuntimeDir(), src );
        Log.i( LOGTAG, "deleting " + dest );
        dest.delete();
    }

    private final void copyAssetFiles()
    {
        String new_sum = readMapFromAssetFile( "sums.sum" ).get( "files.sum" );
        if (new_sum == null) return;  // no assets

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences( this );
        String key = wine_runtime + "/files.sum";
        String old_sum = prefs.getString( key, "" );
        if (old_sum.equals( new_sum )) return;  // no change

        HashMap<String,String> existing_files = readMapFromDiskFile( "files.sum" );
        HashMap<String,String> new_files = readMapFromAssetFile( "files.sum" );
        ArrayList<String> copy_files = new ArrayList<String>();

        for (Map.Entry<String, String> entry : new_files.entrySet())
        {
            String name = entry.getKey();
            if (!entry.getValue().equals( existing_files.remove( name ))) copy_files.add( name );
        }
        existing_files.remove( "files.sum" );
        copy_files.add( "files.sum" );  // last, it records what was extracted

        createProgressDialog( copy_files.size(), "Extracting files..." );

        for (String name : existing_files.keySet()) deleteAssetFile( name );

        for (String name : copy_files)
        {
            copyAssetFile( name );
            runOnUiThread( new Runnable() { public void run() {
                progress_dialog.incrementProgressBy( 1 ); }});
        }
        /* a new runtime may bring new builtin DLLs (an audio driver), which the prefix
         * only gets placeholders for when it is updated, and Wine updates it only
         * when wine.inf changes */
        new File( getRuntimeDir(), "prefix/.update-timestamp" ).delete();

        /* only now, so that an interrupted extraction is redone */
        prefs.edit().putString( key, new_sum ).apply();
    }

    //
    // Generic Wine window class
    //

    private HashMap<Integer,WineWindow> win_map = new HashMap<Integer,WineWindow>();

    protected class WineWindow
    {
        static protected final int HWND_MESSAGE = 0xfffffffd;
        static protected final int SWP_NOZORDER = 0x04;
        static protected final int WS_VISIBLE = 0x10000000;

        protected int hwnd;
        protected int owner;
        protected int style;
        protected float scale;
        protected boolean visible;
        protected Rect visible_rect;
        protected Rect client_rect;
        protected WineWindow parent;
        protected ArrayList<WineWindow> children;
        protected Surface window_surface;
        protected Surface client_surface;
        protected SurfaceTexture window_surftex;
        protected SurfaceTexture client_surftex;
        protected WineWindowGroup window_group;
        protected WineWindowGroup client_group;
        protected ImageReader placeholder;
        protected boolean covered;  /* by a window above it, see update_covered_windows */

        public WineWindow( int w, WineWindow parent, float scale )
        {
            Log.i( LOGTAG, String.format( "create hwnd %08x", w ));
            hwnd = w;
            owner = 0;
            style = 0;
            visible = false;
            visible_rect = client_rect = new Rect( 0, 0, 0, 0 );
            this.parent = parent;
            this.scale = scale;
            children = new ArrayList<WineWindow>();
            win_map.put( w, this );
            if (parent != null) parent.children.add( this );
        }

        public void destroy()
        {
            Log.i( LOGTAG, String.format( "destroy hwnd %08x", hwnd ));
            visible = false;
            win_map.remove( hwnd );
            if (parent != null) parent.children.remove( this );
            destroy_window_groups();
            destroy_placeholder();
            if (parent == desktop_window) update_covered_windows();
        }

        public WineWindowGroup create_window_groups()
        {
            if (client_group != null) return client_group;
            window_group = new WineWindowGroup( this );
            client_group = new WineWindowGroup( this );
            window_group.addView( client_group );
            client_group.set_layout( client_rect.left - visible_rect.left,
                                     client_rect.top - visible_rect.top,
                                     client_rect.right - visible_rect.left,
                                     client_rect.bottom - visible_rect.top );
            if (parent != null)
            {
                parent.create_window_groups();
                if (visible) add_view_to_parent();
                window_group.set_layout( visible_rect.left, visible_rect.top,
                                         visible_rect.right, visible_rect.bottom );
            }
            return client_group;
        }

        public void destroy_window_groups()
        {
            if (window_group != null)
            {
                if (parent != null && parent.client_group != null) remove_view_from_parent();
                window_group.destroy_view();
            }
            if (client_group != null) client_group.destroy_view();
            window_group = null;
            client_group = null;
        }

        public View create_whole_view()
        {
            if (window_group == null) create_window_groups();
            window_group.create_view( false ).layout( 0, 0,
                                                      Math.round( (visible_rect.right - visible_rect.left) * scale ),
                                                      Math.round( (visible_rect.bottom - visible_rect.top) * scale ));
            window_group.set_scale( scale );
            return window_group;
        }

        public void destroy_client_view()
        {
            Log.i( LOGTAG, String.format( "destroying client view %08x", hwnd ));
            if (client_group != null) client_group.destroy_view();
            client_surface = null;
            client_surftex = null;
            destroy_placeholder();
        }

        public void create_client_view()
        {
            if (client_group == null) create_window_groups();
            Log.i( LOGTAG, String.format( "creating client view %08x %s", hwnd, client_rect ));
            client_group.create_view( true ).layout( 0, 0, client_rect.right - client_rect.left,
                                                     client_rect.bottom - client_rect.top );
            if (client_surface == null) set_client_surface( placeholder_surface() );
            if (get_top_window().covered) client_group.get_content_view().setVisibility( View.INVISIBLE );
        }

        public WineWindow get_top_window()
        {
            WineWindow win = this;
            while (win.parent != null && win.parent != desktop_window) win = win.parent;
            return win;
        }

        /* hide the client views of this window and its children, so they don't
         * have a surface; programs keep drawing into the placeholder */
        public void set_covered( boolean covered )
        {
            if (client_group != null && client_group.get_content_view() instanceof WineClientView)
                client_group.get_content_view().setVisibility( covered ? View.INVISIBLE : View.VISIBLE );
            for (WineWindow child : children) child.set_covered( covered );
        }

        protected void add_view_to_parent()
        {
            int pos = parent.client_group.getChildCount() - 1;

            // the content view is always last
            if (pos >= 0 && parent.client_group.getChildAt( pos ) == parent.client_group.get_content_view()) pos--;

            for (int i = 0; i < parent.children.size() && pos >= 0; i++)
            {
                WineWindow child = parent.children.get( i );
                if (child == this) break;
                if (!child.visible) continue;
                if (child == ((WineWindowGroup)parent.client_group.getChildAt( pos )).get_window()) pos--;
            }
            parent.client_group.addView( window_group, pos + 1 );
        }

        protected void remove_view_from_parent()
        {
            parent.client_group.removeView( window_group );
        }

        protected void set_zorder( WineWindow prev )
        {
            int pos = -1;

            parent.children.remove( this );
            if (prev != null) pos = parent.children.indexOf( prev );
            parent.children.add( pos + 1, this );
        }

        protected void sync_views_zorder()
        {
            int i, j;

            for (i = parent.children.size() - 1, j = 0; i >= 0; i--)
            {
                WineWindow child = parent.children.get( i );
                if (!child.visible) continue;
                View child_view = parent.client_group.getChildAt( j );
                if (child_view == parent.client_group.get_content_view()) continue;
                if (child != ((WineWindowGroup)child_view).get_window()) break;
                j++;
            }
            while (i >= 0)
            {
                WineWindow child = parent.children.get( i-- );
                if (child.visible) child.window_group.bringToFront();
            }
        }

        public void pos_changed( int flags, int insert_after, int owner, int style,
                                 Rect window_rect, Rect client_rect, Rect visible_rect )
        {
            boolean was_visible = visible;
            this.visible_rect = visible_rect;
            this.client_rect = client_rect;
            this.style = style;
            this.owner = owner;
            visible = (style & WS_VISIBLE) != 0;

            Log.i( LOGTAG, String.format( "pos changed hwnd %08x after %08x owner %08x style %08x win %s client %s visible %s flags %08x",
                                          hwnd, insert_after, owner, style, window_rect, client_rect, visible_rect, flags ));

            if ((flags & SWP_NOZORDER) == 0 && parent != null) set_zorder( get_window( insert_after ));

            if (window_group != null)
            {
                window_group.set_layout( visible_rect.left, visible_rect.top,
                                         visible_rect.right, visible_rect.bottom );
                if (parent != null)
                {
                    if (!was_visible && (style & WS_VISIBLE) != 0) add_view_to_parent();
                    else if (was_visible && (style & WS_VISIBLE) == 0) remove_view_from_parent();
                    else if (visible && (flags & SWP_NOZORDER) == 0) sync_views_zorder();
                }
            }

            if (client_group != null)
                client_group.set_layout( client_rect.left - visible_rect.left,
                                         client_rect.top - visible_rect.top,
                                         client_rect.right - visible_rect.left,
                                         client_rect.bottom  - visible_rect.top );
            if (parent == desktop_window) update_covered_windows();
        }

        public void set_parent( WineWindow new_parent, float scale )
        {
            Log.i( LOGTAG, String.format( "set parent hwnd %08x parent %08x -> %08x",
                                          hwnd, parent.hwnd, new_parent.hwnd ));
            this.scale = scale;
            if (window_group != null)
            {
                if (visible) remove_view_from_parent();
                new_parent.create_window_groups();
                window_group.set_layout( visible_rect.left, visible_rect.top,
                                         visible_rect.right, visible_rect.bottom );
            }
            parent.children.remove( this );
            parent = new_parent;
            parent.children.add( this );
            if (visible && window_group != null) add_view_to_parent();
        }

        public int get_hwnd()
        {
            return hwnd;
        }

        private void update_surface( boolean is_client )
        {
            if (is_client)
            {
                Log.i( LOGTAG, String.format( "set client surface hwnd %08x %s", hwnd, client_surface ));
                if (client_surface != null) wine_surface_changed( hwnd, client_surface, true );
            }
            else
            {
                Log.i( LOGTAG, String.format( "set window surface hwnd %08x %s", hwnd, window_surface ));
                if (window_surface != null) wine_surface_changed( hwnd, window_surface, false );
            }
        }

        public void set_surface( SurfaceTexture surftex, boolean is_client )
        {
            if (is_client)
            {
                if (surftex == null) client_surface = null;
                else if (surftex != client_surftex)
                {
                    client_surftex = surftex;
                    client_surface = new Surface( surftex );
                }
            }
            else
            {
                if (surftex == null) window_surface = null;
                else if (surftex != window_surftex)
                {
                    window_surftex = surftex;
                    window_surface = new Surface( surftex );
                }
            }
            update_surface( is_client );
        }

        /* Programs create their OpenGL or Vulkan surface before showing the window
         * (SDL, FNA3D), but a SurfaceView only has a surface while it is shown; until
         * then, and while hidden, they draw into this undisplayed one */
        public Surface placeholder_surface()
        {
            if (placeholder == null)
            {
                int width = Math.max( 1, client_rect.right - client_rect.left );
                int height = Math.max( 1, client_rect.bottom - client_rect.top );
                if (Build.VERSION.SDK_INT >= 29)
                    placeholder = ImageReader.newInstance( width, height, PixelFormat.RGBA_8888, 3,
                                                           HardwareBuffer.USAGE_GPU_COLOR_OUTPUT |
                                                           HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE );
                else
                    placeholder = ImageReader.newInstance( width, height, PixelFormat.RGBA_8888, 3 );
                placeholder.setOnImageAvailableListener( new ImageReader.OnImageAvailableListener() {
                    public void onImageAvailable( ImageReader reader )
                    {
                        Image image = reader.acquireLatestImage();  // nobody looks at it
                        if (image != null) image.close();
                    }}, null );
                Log.i( LOGTAG, String.format( "placeholder client surface %08x %dx%d", hwnd, width, height ));
            }
            return placeholder.getSurface();
        }

        public void destroy_placeholder()
        {
            if (placeholder == null) return;
            placeholder.close();
            placeholder = null;
        }

        public void set_client_surface( Surface surface )
        {
            client_surftex = null;
            client_surface = surface;
            update_surface( true );
        }

        public void get_event_pos( MotionEvent event, int[] pos )
        {
            get_event_pos( event, 0, pos );
        }

        public void get_event_pos( MotionEvent event, int index, int[] pos )
        {
            pos[0] = Math.round( event.getX( index ) * scale + window_group.getLeft() );
            pos[1] = Math.round( event.getY( index ) * scale + window_group.getTop() );
        }
    }

    //
    // Window group for a Wine window, optionally containing a content view
    //

    protected class WineWindowGroup extends ViewGroup
    {
        private View content_view;
        private WineWindow win;

        WineWindowGroup( WineWindow win )
        {
            super( WineActivity.this );
            this.win = win;
            setVisibility( View.VISIBLE );
        }

        /* wrapper for layout() making sure that the view is not empty */
        public void set_layout( int left, int top, int right, int bottom )
        {
            left   *= win.scale;
            top    *= win.scale;
            right  *= win.scale;
            bottom *= win.scale;
            if (right <= left + 1) right = left + 2;
            if (bottom <= top + 1) bottom = top + 2;
            layout( left, top, right, bottom );
        }

        @Override
        protected void onLayout( boolean changed, int left, int top, int right, int bottom )
        {
            if (content_view != null) content_view.layout( 0, 0, right - left, bottom - top );
        }

        public void set_scale( float scale )
        {
            if (content_view == null) return;
            content_view.setPivotX( 0 );
            content_view.setPivotY( 0 );
            content_view.setScaleX( scale );
            content_view.setScaleY( scale );
        }

        public View create_view( boolean is_client )
        {
            if (content_view != null) return content_view;
            if (is_client) content_view = new WineClientView( WineActivity.this, win );
            else content_view = new WineView( WineActivity.this, win, false );
            /* the window surface goes below the client views: those of child windows
             * may be drawn by another process (Chromium's GPU process), which Wine
             * doesn't clip out of the window surface of the window's owner */
            if (is_client) addView( content_view );
            else addView( content_view, 0 );
            if (!is_client)
            {
                content_view.setFocusable( true );
                content_view.setFocusableInTouchMode( true );
            }
            return content_view;
        }

        public void destroy_view()
        {
            if (content_view == null) return;
            removeView( content_view );
            content_view = null;
        }

        public View get_content_view()
        {
            return content_view;
        }

        public WineWindow get_window()
        {
            return win;
        }
    }

    // Client area of a window that Wine draws with OpenGL or Vulkan. A SurfaceView's
    // frames go to the compositor in order at the next vsync; a TextureView takes one
    // queued frame each time the app redraws, so frames presented in a burst showed
    // late (typed text in Steam's web UI by up to a second) or, without vsync, stale.

    protected class WineClientView extends SurfaceView implements SurfaceHolder.Callback
    {
        private WineWindow window;

        public WineClientView( Context c, WineWindow win )
        {
            super( c );
            window = win;
            getHolder().addCallback( this );
            setVisibility( VISIBLE );
        }

        public WineWindow get_window()
        {
            return window;
        }

        @Override
        public void surfaceCreated( SurfaceHolder holder )
        {
            Log.i( LOGTAG, String.format( "surfaceCreated win %08x client", window.hwnd ));
            window.set_client_surface( holder.getSurface() );
        }

        @Override
        public void surfaceChanged( SurfaceHolder holder, int format, int width, int height )
        {
            Log.i( LOGTAG, String.format( "surfaceChanged win %08x %dx%d client", window.hwnd, width, height ));
            window.set_client_surface( holder.getSurface() );
        }

        @Override
        public void surfaceDestroyed( SurfaceHolder holder )
        {
            Log.i( LOGTAG, String.format( "surfaceDestroyed win %08x client", window.hwnd ));
            if (WineActivity.this.get_window( window.hwnd ) == window) window.set_client_surface( window.placeholder_surface() );
            else window.set_client_surface( null );
        }
    }

    // View used for the window surface of Wine windows, backed by a TextureView

    protected class WineView extends TextureView implements TextureView.SurfaceTextureListener
    {
        private WineWindow window;
        private boolean is_client;

        public WineView( Context c, WineWindow win, boolean client )
        {
            super( c );
            window = win;
            is_client = client;
            setSurfaceTextureListener( this );
            setVisibility( VISIBLE );
            setOpaque( false );
            setFocusable( true );
            setFocusableInTouchMode( true );
        }

        public WineWindow get_window()
        {
            return window;
        }

        @Override
        public void onSurfaceTextureAvailable( SurfaceTexture surftex, int width, int height )
        {
            Log.i( LOGTAG, String.format( "onSurfaceTextureAvailable win %08x %dx%d %s",
                                          window.hwnd, width, height, is_client ? "client" : "whole" ));
            window.set_surface( surftex, is_client );
        }

        @Override
        public void onSurfaceTextureSizeChanged( SurfaceTexture surftex, int width, int height )
        {
            Log.i( LOGTAG, String.format( "onSurfaceTextureSizeChanged win %08x %dx%d %s",
                                          window.hwnd, width, height, is_client ? "client" : "whole" ));
            window.set_surface( surftex, is_client);
        }

        @Override
        public boolean onSurfaceTextureDestroyed( SurfaceTexture surftex )
        {
            Log.i( LOGTAG, String.format( "onSurfaceTextureDestroyed win %08x %s",
                                          window.hwnd, is_client ? "client" : "whole" ));
            window.set_surface( null, is_client );
            return false;  // hold on to the texture since the app may still be using it
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surftex)
        {
        }

        @TargetApi(24)
        public PointerIcon onResolvePointerIcon( MotionEvent event, int index )
        {
            return current_cursor;
        }

        @Override
        public boolean onGenericMotionEvent( MotionEvent event )
        {
            if (is_client) return false;  // let the whole window handle it
            if (window.parent != null && window.parent != desktop_window) return false;  // let the parent handle it

            if ((event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0)
            {
                int[] pos = new int[2];
                window.get_event_pos( event, pos );
                Log.i( LOGTAG, String.format( "view motion event win %08x action %d pos %d,%d buttons %04x view %d,%d",
                                              window.hwnd, event.getAction(), pos[0], pos[1],
                                              event.getButtonState(), getLeft(), getTop() ));
                if (event.getActionMasked() == MotionEvent.ACTION_SCROLL)
                    return wheel_event( window.hwnd, pos[0], pos[1], event.getButtonState(),
                                        event.getAxisValue( MotionEvent.AXIS_VSCROLL ),
                                        event.getAxisValue( MotionEvent.AXIS_HSCROLL ), false );
                return wine_motion_event( window.hwnd, event.getAction(), pos[0], pos[1],
                                          event.getButtonState(), 0, 0 );
            }
            return super.onGenericMotionEvent(event);
        }

        @Override
        public boolean onTouchEvent( MotionEvent event )
        {
            if (is_client) return false;  // let the whole window handle it
            if (window.parent != null && window.parent != desktop_window) return false;  // let the parent handle it

            if (touchpad_gesture( window, event )) return true;
            if (event.getToolType( event.getActionIndex() ) == MotionEvent.TOOL_TYPE_FINGER)
                return finger_event( window, event );

            int[] pos = new int[2];
            window.get_event_pos( event, pos );
            Log.i( LOGTAG, String.format( "view touch event win %08x action %d pos %d,%d buttons %04x view %d,%d",
                                          window.hwnd, event.getAction(), pos[0], pos[1],
                                          event.getButtonState(), getLeft(), getTop() ));
            return wine_motion_event( window.hwnd, event.getAction(), pos[0], pos[1],
                                      event.getButtonState(), 0, 0 );
        }

        @Override
        public boolean dispatchKeyEvent( KeyEvent event )
        {
            Log.i( LOGTAG, String.format( "view key event win %08x action %d keycode %d (%s)",
                                          window.hwnd, event.getAction(), event.getKeyCode(),
                                          KeyEvent.keyCodeToString( event.getKeyCode() )));;
            boolean ret = wine_keyboard_event( window.hwnd, event.getAction(), event.getKeyCode(),
                                               event.getMetaState() );
            if (!ret) ret = super.dispatchKeyEvent(event);
            return ret;
        }
    }

    // The top-level desktop view group

    protected class TopView extends ViewGroup
    {
        public TopView( Context context, int hwnd )
        {
            super( context );
            desktop_window = new WineWindow( hwnd, null, 1.0f );
            addView( desktop_window.create_whole_view() );
            desktop_window.client_group.bringToFront();

            message_window = new WineWindow( WineWindow.HWND_MESSAGE, null, 1.0f );
            message_window.create_window_groups();
            setFocusable( true );
            setFocusableInTouchMode( true );
        }

        /* Ask the on-screen keyboard to send raw key events, which are
         * forwarded to Wine like hardware keys. */
        @Override
        public boolean onCheckIsTextEditor()
        {
            return true;
        }

        @Override
        public InputConnection onCreateInputConnection( EditorInfo attrs )
        {
            attrs.inputType = InputType.TYPE_NULL;
            attrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
            return new BaseInputConnection( this, false )
            {
                @Override
                public boolean commitText( CharSequence text, int pos )
                {
                    /* some keyboards commit text even with TYPE_NULL */
                    KeyEvent[] events = android.view.KeyCharacterMap.load(
                        android.view.KeyCharacterMap.VIRTUAL_KEYBOARD ).getEvents( text.toString().toCharArray() );
                    if (events == null) return false;
                    for (KeyEvent event : events) sendKeyEvent( event );
                    return true;
                }

                @Override
                public boolean deleteSurroundingText( int before, int after )
                {
                    for (int i = 0; i < before; i++)
                    {
                        sendKeyEvent( new KeyEvent( KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL ));
                        sendKeyEvent( new KeyEvent( KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL ));
                    }
                    return true;
                }
            };
        }

        @Override
        public boolean dispatchKeyEvent( KeyEvent event )
        {
            if (desktop_window != null &&
                wine_keyboard_event( desktop_window.hwnd, event.getAction(), event.getKeyCode(),
                                     event.getMetaState() ))
                return true;
            return super.dispatchKeyEvent( event );
        }

        @Override
        protected void onSizeChanged( int width, int height, int old_width, int old_height )
        {
            Log.i( LOGTAG, String.format( "desktop size %dx%d", width, height ));
            wine_desktop_changed( width, height );
        }

        @Override
        protected void onLayout( boolean changed, int left, int top, int right, int bottom )
        {
            // nothing to do
        }
    }

    private TopView top_view;
    private View root_view;

    // Mouse wheel: Android reports notches as floats (fractions with high
    // resolution wheels and touchpads), Wine takes WHEEL_DELTA (120) units

    private static final int WHEEL_DELTA = 120;
    private float wheel_v, wheel_h;  // fractions of WHEEL_DELTA not sent yet

    /* v and h are in notches, positive scrolls up / right */
    private boolean wheel_event( int hwnd, int x, int y, int state, float v, float h, boolean relative )
    {
        wheel_v += v * WHEEL_DELTA;
        wheel_h += h * WHEEL_DELTA;
        int dv = (int)wheel_v, dh = (int)wheel_h;
        wheel_v -= dv;
        wheel_h -= dh;
        if (dv == 0 && dh == 0) return true;
        if (relative) return wine_relative_motion_event( MotionEvent.ACTION_SCROLL, 0, 0, state, dv, dh );
        return wine_motion_event( hwnd, MotionEvent.ACTION_SCROLL, x, y, state, dv, dh );
    }

    // Touchpad gestures: Android 14+ hands them to apps as fake fingers that
    // move from the cursor position. A two-finger scroll would be a left-button
    // drag, so turn the fingers' movement into wheel events at the cursor;
    // three-finger swipes and pinches are for the system, drop them.

    private static final float SCROLL_DP_PER_NOTCH = 40.0f;
    private int swipe_id = -1;
    private float swipe_x, swipe_y;
    private int[] swipe_pos = new int[2];

    private boolean touchpad_gesture( WineWindow window, MotionEvent event )
    {
        if (Build.VERSION.SDK_INT < 34) return false;
        int classification = event.getClassification();
        if (classification == 4 /* MULTI_FINGER_SWIPE, hidden */ || classification == 5 /* PINCH, API 35 */)
            return true;
        if (classification != MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE) return false;

        int action = event.getActionMasked();
        int index = swipe_id == -1 ? -1 : event.findPointerIndex( swipe_id );
        if (action == MotionEvent.ACTION_DOWN || index < 0)
        {
            /* the fingers start at the cursor, scroll there */
            swipe_id = event.getPointerId( 0 );
            swipe_x = event.getX( 0 );
            swipe_y = event.getY( 0 );
            window.get_event_pos( event, 0, swipe_pos );
            Log.i( LOGTAG, String.format( "touchpad scroll win %08x pos %d,%d", window.hwnd, swipe_pos[0], swipe_pos[1] ));
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE)
        {
            float x = event.getX( index ), y = event.getY( index );
            float notch = SCROLL_DP_PER_NOTCH * getResources().getDisplayMetrics().density;
            /* like dragging content on a touchscreen: fingers down scroll up */
            wheel_event( window.hwnd, swipe_pos[0], swipe_pos[1], 0,
                         (y - swipe_y) / notch, (swipe_x - x) / notch, false );
            swipe_x = x;
            swipe_y = y;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
        {
            swipe_id = -1;
            wheel_v = wheel_h = 0;
        }
        return true;
    }

    // Fingers act as mouse buttons: the first one down is the left button, a
    // second one down at the same time the right button, and the cursor follows
    // the newest. Clicks happen on touch, and alternating fingers in games (two
    // keys in rhythm games) each register; further fingers are ignored.

    private int left_finger = -1, right_finger = -1, cursor_finger = -1;
    private int finger_buttons;
    private WineWindow cursor_finger_window;

    private boolean finger_event( WineWindow window, MotionEvent event )
    {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        int id = event.getPointerId( index );
        int[] pos = new int[2];

        switch (action)
        {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_POINTER_DOWN:
            int button;
            if (left_finger == -1) { left_finger = id; button = MotionEvent.BUTTON_PRIMARY; }
            else if (right_finger == -1) { right_finger = id; button = MotionEvent.BUTTON_SECONDARY; }
            else return true;
            cursor_finger = id;
            cursor_finger_window = window;
            finger_buttons |= button;
            window.get_event_pos( event, index, pos );
            return wine_motion_event( window.hwnd, MotionEvent.ACTION_BUTTON_PRESS, pos[0], pos[1], finger_buttons, 0, 0 );

        case MotionEvent.ACTION_MOVE:
            if (window != cursor_finger_window) return true;
            index = event.findPointerIndex( cursor_finger );
            if (index < 0) return true;
            window.get_event_pos( event, index, pos );
            return wine_motion_event( window.hwnd, MotionEvent.ACTION_MOVE, pos[0], pos[1], finger_buttons, 0, 0 );

        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP:
        case MotionEvent.ACTION_CANCEL:
            int released = 0;
            if (action == MotionEvent.ACTION_CANCEL)
            {
                released = finger_buttons;
                left_finger = right_finger = -1;
            }
            else if (id == left_finger) { left_finger = -1; released = MotionEvent.BUTTON_PRIMARY; }
            else if (id == right_finger) { right_finger = -1; released = MotionEvent.BUTTON_SECONDARY; }
            if (released == 0) return true;
            finger_buttons &= ~released;
            if (id == cursor_finger || action == MotionEvent.ACTION_CANCEL)
            {
                /* release where the finger was lifted, then follow the remaining one */
                window.get_event_pos( event, index, pos );
                cursor_finger = left_finger != -1 ? left_finger : right_finger;
                if (cursor_finger == -1) cursor_finger_window = null;
            }
            else
            {
                /* release where the cursor is, the other finger keeps it */
                int i = event.findPointerIndex( cursor_finger );
                window.get_event_pos( event, i >= 0 ? i : index, pos );
            }
            return wine_motion_event( window.hwnd, MotionEvent.ACTION_BUTTON_RELEASE, pos[0], pos[1], finger_buttons, 0, 0 );
        }
        return true;
    }

    // Pointer capture: while Wine hides the cursor (games doing mouselook), take
    // the pointer from Android and send relative motion. Absolute positions stop
    // at the screen edge and make raw input report absolute coordinates.

    private int captured_buttons;
    private int touchpad_id = -1;
    private float touchpad_x, touchpad_y, captured_dx, captured_dy;
    private float mouse_speed = 1.0f;

    private void update_pointer_capture()
    {
        if (root_view == null) return;
        mouse_speed = getSharedPreferences( PREFS_LAUNCHER, MODE_PRIVATE ).getFloat( PREF_MOUSE_SPEED, 1.0f );
        if (cursor_hidden && hasWindowFocus())
        {
            if (!root_view.hasPointerCapture())
            {
                Log.i( LOGTAG, "requesting pointer capture" );
                root_view.requestPointerCapture();
            }
        }
        else if (root_view.hasPointerCapture())
        {
            Log.i( LOGTAG, "releasing pointer capture" );
            root_view.releasePointerCapture();
        }
    }

    @Override
    public void onWindowFocusChanged( boolean has_focus )
    {
        super.onWindowFocusChanged( has_focus );
        update_pointer_capture();  // Android drops the capture when the window loses focus
    }

    private boolean captured_pointer_event( MotionEvent event )
    {
        int action = event.getActionMasked();
        int buttons = event.getButtonState();

        if (event.isFromSource( InputDevice.SOURCE_MOUSE_RELATIVE ))
        {
            for (int i = 0; i < event.getHistorySize(); i++)
            {
                captured_dx += event.getHistoricalX( i ) * mouse_speed;
                captured_dy += event.getHistoricalY( i ) * mouse_speed;
            }
            captured_dx += event.getX() * mouse_speed;
            captured_dy += event.getY() * mouse_speed;
        }
        else if (event.isFromSource( InputDevice.SOURCE_TOUCHPAD ))
        {
            // captured touchpad events report finger positions on the pad,
            // follow the first finger and scale the pad's width to the screen's
            int id = event.getPointerId( 0 );
            float x = event.getX( 0 ), y = event.getY( 0 );
            InputDevice.MotionRange range = event.getDevice() != null ?
                event.getDevice().getMotionRange( MotionEvent.AXIS_X, event.getSource() ) : null;
            float scale = (range != null && range.getRange() > 0) ? root_view.getWidth() / range.getRange() : 1.0f;
            scale *= mouse_speed;

            if (action == MotionEvent.ACTION_MOVE && id == touchpad_id)
            {
                captured_dx += (x - touchpad_x) * scale;
                captured_dy += (y - touchpad_y) * scale;
            }
            touchpad_id = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ? -1 : id;
            touchpad_x = x;
            touchpad_y = y;
        }
        else return false;

        int dx = (int)captured_dx, dy = (int)captured_dy;
        captured_dx -= dx;
        captured_dy -= dy;

        switch (action)
        {
        case MotionEvent.ACTION_BUTTON_PRESS:
        case MotionEvent.ACTION_BUTTON_RELEASE:
            if (buttons == captured_buttons) break;
            if (dx != 0 || dy != 0)
                wine_relative_motion_event( MotionEvent.ACTION_MOVE, dx, dy, captured_buttons, 0, 0 );
            captured_buttons = buttons;
            return wine_relative_motion_event( action, 0, 0, buttons, 0, 0 );
        case MotionEvent.ACTION_SCROLL:
            if (dx != 0 || dy != 0)
                wine_relative_motion_event( MotionEvent.ACTION_MOVE, dx, dy, captured_buttons, 0, 0 );
            return wheel_event( 0, 0, 0, captured_buttons, event.getAxisValue( MotionEvent.AXIS_VSCROLL ),
                                event.getAxisValue( MotionEvent.AXIS_HSCROLL ), true );
        }
        // the button state only changes through BUTTON_PRESS/RELEASE, so the
        // driver doesn't mistake those for touch events
        if (dx != 0 || dy != 0)
            wine_relative_motion_event( MotionEvent.ACTION_MOVE, dx, dy, captured_buttons, 0, 0 );
        return true;
    }

    /* SurfaceViews are stacked by the order their surfaces were created in, not by
     * the order of their views, so the client view of a window behind another one
     * showed through the hole the front window's own client view punches into the
     * activity's surface (Steam's browser over a fullscreen game after a resize
     * recreated it). Windows completely covered by the ones above them don't need
     * to be shown, so their client views are hidden. */
    private void update_covered_windows()
    {
        if (desktop_window == null) return;
        android.graphics.Region above = new android.graphics.Region();

        for (WineWindow win : desktop_window.children)  /* top to bottom */
        {
            if (!win.visible) continue;
            android.graphics.Region rest = new android.graphics.Region( win.visible_rect );
            boolean covered = !rest.op( above, android.graphics.Region.Op.DIFFERENCE );  /* empty */
            if (covered != win.covered)
            {
                Log.i( LOGTAG, String.format( "window %08x %s", win.hwnd, covered ? "covered" : "uncovered" ));
                win.covered = covered;
                win.set_covered( covered );
            }
            above.op( win.visible_rect, android.graphics.Region.Op.UNION );
        }
    }

    private void toggleSoftKeyboard()
    {
        if (top_view == null) return;
        top_view.requestFocus();
        InputMethodManager imm = (InputMethodManager)getSystemService( Context.INPUT_METHOD_SERVICE );
        imm.toggleSoftInput( InputMethodManager.SHOW_FORCED, 0 );
    }

    protected WineWindow get_window( int hwnd )
    {
        return win_map.get( hwnd );
    }

    // Entry points for the device driver

    public void create_desktop_window( int hwnd )
    {
        Log.i( LOGTAG, String.format( "create desktop view %08x", hwnd ));
        top_view = new TopView( this, hwnd );
        FrameLayout frame = new FrameLayout( this ) {
            @Override
            public boolean dispatchCapturedPointerEvent( MotionEvent event )
            {
                return captured_pointer_event( event );
            }
        };
        root_view = frame;
        frame.addView( top_view );
        Button kbd = new Button( this );
        kbd.setText( "\u2328" );
        kbd.setAlpha( 0.6f );
        kbd.setFocusable( false );
        kbd.setOnClickListener( new View.OnClickListener() {
            public void onClick( View v ) { toggleSoftKeyboard(); }} );
        frame.addView( kbd, new FrameLayout.LayoutParams( FrameLayout.LayoutParams.WRAP_CONTENT,
                                                          FrameLayout.LayoutParams.WRAP_CONTENT,
                                                          Gravity.BOTTOM | Gravity.END ));
        setContentView( frame );
        top_view.requestFocus();
        progress_dialog.dismiss();
        wine_config_changed( getResources().getConfiguration().densityDpi );
    }

    public void create_window( int hwnd, boolean opengl, int parent, float scale, int pid )
    {
        WineWindow win = get_window( hwnd );
        if (win == null)
        {
            win = new WineWindow( hwnd, get_window( parent ), scale );
            win.create_window_groups();
            if (win.parent == desktop_window) win.create_whole_view();
        }
        if (opengl) win.create_client_view();
    }

    public void destroy_window( int hwnd )
    {
        WineWindow win = get_window( hwnd );
        if (win != null) win.destroy();
    }

    public void destroy_client_view( int hwnd )
    {
        WineWindow win = get_window( hwnd );
        if (win != null) win.destroy_client_view();
    }

    public void set_window_parent( int hwnd, int parent, float scale, int pid )
    {
        WineWindow win = get_window( hwnd );
        if (win == null) return;
        win.set_parent( get_window( parent ), scale );
        if (win.parent == desktop_window) win.create_whole_view();
    }

    @TargetApi(24)
    public void set_cursor( int id, int width, int height, int hotspotx, int hotspoty, int bits[] )
    {
        Log.i( LOGTAG, String.format( "set_cursor id %d size %dx%d hotspot %dx%d", id, width, height, hotspotx, hotspoty ));
        if (bits != null)
        {
            Bitmap bitmap = Bitmap.createBitmap( bits, width, height, Bitmap.Config.ARGB_8888 );
            current_cursor = PointerIcon.create( bitmap, hotspotx, hotspoty );
        }
        else current_cursor = PointerIcon.getSystemIcon( this, id );
        cursor_hidden = (bits == null && id == PointerIcon.TYPE_NULL);
        update_pointer_capture();
    }

    public void window_pos_changed( int hwnd, int flags, int insert_after, int owner, int style,
                                    Rect window_rect, Rect client_rect, Rect visible_rect )
    {
        WineWindow win = get_window( hwnd );
        if (win != null)
            win.pos_changed( flags, insert_after, owner, style, window_rect, client_rect, visible_rect );
    }

    public void createDesktopWindow( final int hwnd )
    {
        runOnUiThread( new Runnable() { public void run() { create_desktop_window( hwnd ); }} );
    }

    public void createWindow( final int hwnd, final boolean opengl, final int parent, final float scale, final int pid )
    {
        runOnUiThread( new Runnable() { public void run() { create_window( hwnd, opengl, parent, scale, pid ); }} );
    }

    public void destroyWindow( final int hwnd )
    {
        runOnUiThread( new Runnable() { public void run() { destroy_window( hwnd ); }} );
    }

    public void destroyClientView( final int hwnd )
    {
        runOnUiThread( new Runnable() { public void run() { destroy_client_view( hwnd ); }} );
    }

    public void setParent( final int hwnd, final int parent, final float scale, final int pid )
    {
        runOnUiThread( new Runnable() { public void run() { set_window_parent( hwnd, parent, scale, pid ); }} );
    }

    public void setCursor( final int id, final int width, final int height,
                           final int hotspotx, final int hotspoty, final int bits[] )
    {
        if (Build.VERSION.SDK_INT < 24) return;
        runOnUiThread( new Runnable() { public void run() { set_cursor( id, width, height, hotspotx, hotspoty, bits ); }} );
    }

    public void windowPosChanged( final int hwnd, final int flags, final int insert_after,
                                  final int owner, final int style,
                                  final int window_left, final int window_top,
                                  final int window_right, final int window_bottom,
                                  final int client_left, final int client_top,
                                  final int client_right, final int client_bottom,
                                  final int visible_left, final int visible_top,
                                  final int visible_right, final int visible_bottom )
    {
        final Rect window_rect = new Rect( window_left, window_top, window_right, window_bottom );
        final Rect client_rect = new Rect( client_left, client_top, client_right, client_bottom );
        final Rect visible_rect = new Rect( visible_left, visible_top, visible_right, visible_bottom );
        runOnUiThread( new Runnable() {
            public void run() { window_pos_changed( hwnd, flags, insert_after, owner, style,
                                                    window_rect, client_rect, visible_rect ); }} );
    }
}
