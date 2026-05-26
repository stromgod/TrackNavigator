package com.example.tracknavigator;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

import java.io.IOException;
import java.io.InputStream;

/** Loads bot icons from assets (repo-root `Botik/` via Gradle). */
public final class BotAssets {

    public static void setBotIcon(Context ctx, ImageView target, String assetFileName) {
        if (ctx == null || target == null || assetFileName == null) return;
        Bitmap bmp = loadScaledBitmap(ctx, assetFileName, 96, 96);
        if (bmp != null) target.setImageBitmap(bmp);
    }

    private static Bitmap loadScaledBitmap(Context ctx, String assetFileName, int reqW, int reqH) {
        AssetManager am = ctx.getAssets();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;

        try (InputStream is = am.open(assetFileName)) {
            BitmapFactory.decodeStream(is, null, bounds);
        } catch (IOException ignored) {
            return null;
        }

        int inSampleSize = 1;
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            int halfW = Math.max(1, bounds.outWidth / 2);
            int halfH = Math.max(1, bounds.outHeight / 2);
            while ((halfW / inSampleSize) >= reqW && (halfH / inSampleSize) >= reqH) {
                inSampleSize *= 2;
            }
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = inSampleSize;

        try (InputStream is = am.open(assetFileName)) {
            return BitmapFactory.decodeStream(is, null, opts);
        } catch (IOException ignored) {
            return null;
        }
    }

    private BotAssets() {}
}

