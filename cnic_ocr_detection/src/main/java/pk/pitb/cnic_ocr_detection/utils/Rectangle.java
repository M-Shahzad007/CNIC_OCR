package pk.pitb.cnic_ocr_detection.utils;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.View;

public class Rectangle extends View {
    Paint paint = new Paint();
    Context context;

    public Rectangle(Context context) {
        super(context);
        this.context = context;
    }

    @Override
    public void onDraw(Canvas canvas) {

        DisplayMetrics displayMetrics = new DisplayMetrics();
        ((Activity) context).getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int height = displayMetrics.heightPixels;
        int width = displayMetrics.widthPixels;

        paint.setColor(Color.GREEN);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth((int) (((double) width) * 0.005));
        Rect rect = new Rect((int) (((double) width) * 0.15), (int) (((double) height) * 0.1), (int) (((double) width) * 0.85), (int) (((double) height) * 0.9));
        canvas.drawRect(rect, paint);
    }
}