/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Author:   Dmitry Baryshnikov (aka Bishop), bishop.dev@gmail.com
 * Author:   NikitaFeodonit, nfeodonit@yandex.com
 * Author:   Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright (c) 2012-2015. NextGIS, info@nextgis.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.nextgis.maplib.display;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.nextgis.maplib.datasource.GeoEnvelope;
import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.map.Layer;
import com.nextgis.maplib.map.VectorLayer;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.VectorCacheItem;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

import static com.nextgis.maplib.util.Constants.DEFAULT_EXECUTION_DELAY;
import static com.nextgis.maplib.util.Constants.DRAWING_SEPARATE_THREADS;
import static com.nextgis.maplib.util.Constants.JSON_NAME_KEY;
import static com.nextgis.maplib.util.Constants.KEEP_ALIVE_TIME;
import static com.nextgis.maplib.util.Constants.KEEP_ALIVE_TIME_UNIT;
import static com.nextgis.maplib.util.Constants.TAG;
import static com.nextgis.maplib.util.Constants.TERMINATE_TIME;


public class SimpleFeatureRenderer
        extends Renderer
{

    protected Style              mStyle;
    protected ThreadPoolExecutor mDrawThreadPool;
    protected int                mGeomCompleteCount;
    protected final Object lock = new Object();

    public static final String JSON_STYLE_KEY = "style";


    public SimpleFeatureRenderer(Layer layer)
    {
        super(layer);
        mStyle = null;
    }


    public SimpleFeatureRenderer(
            Layer layer,
            Style style)
    {
        super(layer);
        mStyle = style;
    }


    @Override
    public void runDraw(final GISDisplay display)
    {
        if (null == mStyle) {
            Log.d(TAG, "mStyle == null");
            return;
        }


        GeoEnvelope env = display.getBounds();
        final VectorLayer vectorLayer = (VectorLayer) mLayer;
        GeoEnvelope layerEnv = vectorLayer.getExtents();

        if (null == layerEnv || !env.intersects(layerEnv)) {
            final Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    vectorLayer.onDrawFinished(vectorLayer.getId(), 1.0f);
                }
            }, DEFAULT_EXECUTION_DELAY);
            return;
        }

        //add drawing routine
        final List<VectorCacheItem> cache = vectorLayer.getVectorCache();

        if (cache.size() == 0) {
            final Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    vectorLayer.onDrawFinished(vectorLayer.getId(), 1.0f);
                }
            }, DEFAULT_EXECUTION_DELAY);
            return;
        }

        cancelDraw();

        int threadCount = DRAWING_SEPARATE_THREADS;
        synchronized (lock) {
            mDrawThreadPool = new ThreadPoolExecutor(
                    threadCount, threadCount, KEEP_ALIVE_TIME, KEEP_ALIVE_TIME_UNIT,
                    new LinkedBlockingQueue<Runnable>());
        }

        synchronized (mLayer) {
            mGeomCompleteCount = 0;
        }

        for (int i = 0; i < cache.size(); i++) {
            final VectorCacheItem item = cache.get(i);
            final Style style = getStyle(item.getId());

            mDrawThreadPool.execute(
                    new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            android.os.Process.setThreadPriority(
                                    Constants.DEFAULT_DRAW_THREAD_PRIORITY);

                            GeoGeometry geometry = item.getGeoGeometry();
                            if (null != geometry) {
                                style.onDraw(geometry, display);
                            }

                            synchronized (mLayer) {
                                mGeomCompleteCount++;
                                float percent = (float) (mGeomCompleteCount) / cache.size();
                                vectorLayer.onDrawFinished(vectorLayer.getId(), percent);

                                //Log.d(TAG, "Vector percent: " + percent + " complete: " +
                                //        mGeomCompleteCount + " geom count: " + cache.size() +
                                //        " layer :" + mLayer.getName());
                            }
                            //vectorLayer.onDrawFinished(vectorLayer.getId(), 1);
                        }

                    });

        }
        mDrawThreadPool.shutdown();
    }


    /**
     * If subclass's getStyle(long featureId) changes style params then must be so in the method
     * body:
     * <pre> {@code
     * try {
     *     Style styleClone = mStyle.clone();
     *     // changing styleClone params
     *     return styleClone;
     * } catch (CloneNotSupportedException e) {
     *     e.printStackTrace();
     *     return mStyle;
     *     // or treat this situation instead
     * }
     * } </pre>
     * or
     * <pre> {@code
     * Style style = new Style(); // or from subclass of Style
     * // changing style params
     * return style;
     * } </pre>
     */
    protected Style getStyle(long featureId)
    {
        return mStyle;
    }


    @Override
    public void cancelDraw()
    {
        if (mDrawThreadPool != null) {
            synchronized (lock) {
                mDrawThreadPool.shutdownNow();
            }
            try {
                mDrawThreadPool.awaitTermination(TERMINATE_TIME, KEEP_ALIVE_TIME_UNIT);
                mDrawThreadPool.purge();
            } catch (InterruptedException e) {
                e.printStackTrace();
                //mDrawThreadPool.shutdownNow();
                //Thread.currentThread().interrupt();
            }
        }
    }


    public Style getStyle()
    {
        return mStyle;
    }


    @Override
    public JSONObject toJSON()
            throws JSONException
    {
        JSONObject rootJsonObject = new JSONObject();
        rootJsonObject.put(JSON_NAME_KEY, "SimpleFeatureRenderer");

        if (null != mStyle) {
            rootJsonObject.put(JSON_STYLE_KEY, mStyle.toJSON());
        }

        return rootJsonObject;
    }


    @Override
    public void fromJSON(JSONObject jsonObject)
            throws JSONException
    {
        JSONObject styleJsonObject = jsonObject.getJSONObject(JSON_STYLE_KEY);
        String styleName = styleJsonObject.getString(JSON_NAME_KEY);
        switch (styleName) {
            case "SimpleMarkerStyle":
                mStyle = new SimpleMarkerStyle();
                mStyle.fromJSON(styleJsonObject);
                break;
            case "SimpleTextMarkerStyle":
                mStyle = new SimpleTextMarkerStyle();
                mStyle.fromJSON(styleJsonObject);
                break;
            case "SimpleLineStyle":
                mStyle = new SimpleLineStyle();
                mStyle.fromJSON(styleJsonObject);
                break;
            case "SimpleTextLineStyle":
                mStyle = new SimpleTextLineStyle();
                mStyle.fromJSON(styleJsonObject);
                break;
            case "SimplePolygonStyle":
                mStyle = new SimplePolygonStyle();
                mStyle.fromJSON(styleJsonObject);
                break;
            default:
                throw new JSONException("Unknown style type: " + styleName);
        }
    }
}
