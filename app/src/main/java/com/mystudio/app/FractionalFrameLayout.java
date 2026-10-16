package com.mystudio.app;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * إطار يضع أبناءه بنسب (0..1) من مقاس الشاشة بدل المقاسات الثابتة،
 * لذلك تبقى شاشة البداية في نفس المكان على كل الأجهزة.
 */
public class FractionalFrameLayout extends FrameLayout {

    public FractionalFrameLayout(Context context) {
        super(context);
    }

    public FractionalFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FractionalFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public static class LayoutParams extends FrameLayout.LayoutParams {

        public float frx = 0.5f;
        public float fry = 0.5f;
        public boolean fractional = false;

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
            if (attrs != null) {
                TypedArray a = c.obtainStyledAttributes(attrs, R.styleable.FractionalFrameLayout);
                frx = a.getFloat(R.styleable.FractionalFrameLayout_frx, 0.5f);
                fry = a.getFloat(R.styleable.FractionalFrameLayout_fry, 0.5f);
                a.recycle();
            }
            fractional = true;
        }

        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }

        public LayoutParams(int w, int h) {
            super(w, h);
        }
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return new LayoutParams(p);
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int w = getWidth();
        int h = getHeight();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            ViewGroup.LayoutParams raw = child.getLayoutParams();
            boolean full = raw.width == ViewGroup.LayoutParams.MATCH_PARENT
                    && raw.height == ViewGroup.LayoutParams.MATCH_PARENT;
            if (raw instanceof LayoutParams && ((LayoutParams) raw).fractional && !full) {
                LayoutParams lp = (LayoutParams) raw;
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();
                int cx = Math.round(w * lp.frx);
                int cy = Math.round(h * lp.fry);
                int l = cx - cw / 2;
                int t = cy - ch / 2;
                child.layout(l, t, l + cw, t + ch);
            } else {
                child.layout(0, 0, w, h);
            }
        }
    }
}
