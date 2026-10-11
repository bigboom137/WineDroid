package com.winedroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/* Look of the launcher: dark navy surfaces, thin outlines and a blue accent,
 * like WinNative's library */
final class Ui
{
    static final int BACKGROUND = 0xFF12121B;
    static final int SURFACE = 0xFF1C1C2A;
    static final int SURFACE_ALT = 0xFF21212A;
    static final int OUTLINE = 0xFF2A2A3A;
    static final int ACCENT = 0xFF1A9FFF;
    static final int TEXT = 0xFFF0F4FF;
    static final int TEXT_SECONDARY = 0xFF93A6BC;
    static final int DANGER = 0xFFFF7A88;

    static final Typeface MEDIUM = Typeface.create( "sans-serif-medium", Typeface.NORMAL );

    private Ui() {}

    static int dp( Context c, float v )
    {
        return (int)TypedValue.applyDimension( TypedValue.COMPLEX_UNIT_DIP, v,
                                               c.getResources().getDisplayMetrics() );
    }

    static GradientDrawable rounded( Context c, int fill, int stroke, float radius_dp )
    {
        GradientDrawable d = new GradientDrawable();
        d.setColor( fill );
        d.setCornerRadius( dp( c, radius_dp ));
        if (stroke != 0) d.setStroke( dp( c, 1 ), stroke );
        return d;
    }

    /* rounded background with the touch ripple */
    static void card( View v, int fill, int stroke, float radius_dp )
    {
        Context c = v.getContext();
        GradientDrawable mask = rounded( c, Color.WHITE, 0, radius_dp );
        v.setBackground( new RippleDrawable( ColorStateList.valueOf( 0x331A9FFF ),
                                             rounded( c, fill, stroke, radius_dp ), mask ));
        v.setClipToOutline( true );
    }

    static TextView text( Context c, String s, float sp, int color, boolean medium )
    {
        TextView t = new TextView( c );
        t.setText( s );
        t.setTextSize( TypedValue.COMPLEX_UNIT_SP, sp );
        t.setTextColor( color );
        if (medium) t.setTypeface( MEDIUM );
        return t;
    }

    static TextView section( Context c, String s )
    {
        TextView t = text( c, s, 13, TEXT_SECONDARY, true );
        t.setAllCaps( true );
        t.setLetterSpacing( 0.08f );
        t.setPadding( 0, dp( c, 28 ), 0, dp( c, 10 ));
        return t;
    }

    /* pill shaped button; filled for the main action */
    static TextView pill( Context c, String s, boolean filled, View.OnClickListener l )
    {
        TextView t = text( c, s, 14, filled ? Color.WHITE : TEXT, true );
        t.setPadding( dp( c, 16 ), dp( c, 9 ), dp( c, 16 ), dp( c, 9 ));
        card( t, filled ? ACCENT : SURFACE, filled ? 0 : OUTLINE, 20 );
        t.setOnClickListener( l );
        return t;
    }

    /* lays its children out in as many equal columns as fit, at least min_width_dp wide */
    static class Grid extends ViewGroup
    {
        private final int min_width, gap;

        Grid( Context c, float min_width_dp, float gap_dp )
        {
            super( c );
            min_width = dp( c, min_width_dp );
            gap = dp( c, gap_dp );
        }

        private int columns( int width )
        {
            return Math.max( 1, (width + gap) / (min_width + gap) );
        }

        @Override
        protected void onMeasure( int wspec, int hspec )
        {
            int width = MeasureSpec.getSize( wspec );
            int cols = columns( width );
            int child_width = (width - gap * (cols - 1)) / cols;
            int height = 0, row_height = 0;

            for (int i = 0; i < getChildCount(); i++)
            {
                View child = getChildAt( i );
                child.measure( MeasureSpec.makeMeasureSpec( child_width, MeasureSpec.EXACTLY ),
                               MeasureSpec.makeMeasureSpec( 0, MeasureSpec.UNSPECIFIED ));
                row_height = Math.max( row_height, child.getMeasuredHeight() );
                if (i % cols == cols - 1 || i == getChildCount() - 1)
                {
                    height += row_height + (height > 0 ? gap : 0);
                    row_height = 0;
                }
            }
            setMeasuredDimension( width, height );
        }

        @Override
        protected void onLayout( boolean changed, int l, int t, int r, int b )
        {
            int cols = columns( r - l );
            int x = 0, y = 0, row_height = 0;

            for (int i = 0; i < getChildCount(); i++)
            {
                View child = getChildAt( i );
                if (i % cols == 0 && i > 0)
                {
                    x = 0;
                    y += row_height + gap;
                    row_height = 0;
                }
                child.layout( x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight() );
                x += child.getMeasuredWidth() + gap;
                row_height = Math.max( row_height, child.getMeasuredHeight() );
            }
        }
    }

    /* ImageView keeping a fixed aspect ratio of its width */
    static class AspectImage extends ImageView
    {
        private final float ratio;

        AspectImage( Context c, float ratio )
        {
            super( c );
            this.ratio = ratio;
            setScaleType( ScaleType.CENTER_CROP );
        }

        @Override
        protected void onMeasure( int wspec, int hspec )
        {
            int width = MeasureSpec.getSize( wspec );
            setMeasuredDimension( width, Math.round( width / ratio ));
        }
    }

    // Artwork, decoded off the UI thread and downsampled to the view's size

    private static final ExecutorService loader = Executors.newFixedThreadPool( 2 );
    private static final Handler main = new Handler( Looper.getMainLooper() );
    private static final LruCache<String,Bitmap> cache = new LruCache<String,Bitmap>( 48 * 1024 * 1024 ) {
        @Override protected int sizeOf( String key, Bitmap b ) { return b.getByteCount(); }};

    static void load( final ImageView view, final File file, final int target_width )
    {
        final String key = file.getPath() + "@" + target_width;
        view.setTag( key );
        Bitmap cached = cache.get( key );
        if (cached != null)
        {
            view.setImageBitmap( cached );
            return;
        }
        loader.execute( new Runnable() { public void run()
        {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile( file.getPath(), opts );
            opts.inSampleSize = 1;
            while (opts.outWidth / (opts.inSampleSize * 2) >= target_width) opts.inSampleSize *= 2;
            opts.inJustDecodeBounds = false;
            final Bitmap b = BitmapFactory.decodeFile( file.getPath(), opts );
            if (b == null) return;
            cache.put( key, b );
            main.post( new Runnable() { public void run()
            {
                if (key.equals( view.getTag() )) view.setImageBitmap( b );
            }} );
        }} );
    }
}
