package org.telegram.ui.finance;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.finance.Money;
import org.telegram.ui.habits.HabitUi;

import java.time.LocalDate;
import java.util.List;

/** FORK MOD (Finance): simple line chart of {day, value} points with a gradient fill and min/max labels */
public class ChartView extends View {
    private List<double[]> points;
    private String code = "UZS";
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path area = new Path();

    public ChartView(Context ctx) {
        super(ctx);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(2.5f));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        text.setTextSize(dp(11));
        text.setTypeface(AndroidUtilities.bold());
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(1);
    }

    public void setData(List<double[]> points, String code) {
        this.points = points;
        this.code = code;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (points == null || points.size() < 2) {
            text.setColor(HabitUi.sub());
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("Not enough data yet", getWidth() / 2f, getHeight() / 2f, text);
            return;
        }
        int n = points.size();
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double[] p : points) { min = Math.min(min, p[1]); max = Math.max(max, p[1]); }
        if (max - min < 1e-9) { max += 1; min -= 1; }
        double pad = (max - min) * 0.08;
        min -= pad; max += pad;
        float top = dp(18), bottom = getHeight() - dp(18), left = dp(4), right = getWidth() - dp(4);
        double x0 = points.get(0)[0], x1 = points.get(n - 1)[0];
        if (x1 - x0 < 1) x1 = x0 + 1;
        boolean up = points.get(n - 1)[1] >= points.get(0)[1];
        int color = up ? FinanceUi.income() : FinanceUi.expense();
        line.setColor(color);
        fill.setShader(new LinearGradient(0, top, 0, bottom, HabitUi.withAlpha(color, 0x55), HabitUi.withAlpha(color, 0x00), Shader.TileMode.CLAMP));
        grid.setColor(HabitUi.withAlpha(HabitUi.sub(), 0x30));
        c.drawLine(left, top, right, top, grid);
        c.drawLine(left, bottom, right, bottom, grid);
        path.reset();
        area.reset();
        for (int i = 0; i < n; i++) {
            float x = (float) (left + (points.get(i)[0] - x0) / (x1 - x0) * (right - left));
            float y = (float) (bottom - (points.get(i)[1] - min) / (max - min) * (bottom - top));
            if (i == 0) { path.moveTo(x, y); area.moveTo(x, bottom); area.lineTo(x, y); }
            else { path.lineTo(x, y); area.lineTo(x, y); }
            if (i == n - 1) { area.lineTo(x, bottom); area.close(); }
        }
        c.drawPath(area, fill);
        c.drawPath(path, line);
        text.setColor(HabitUi.sub());
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(Money.compact(max - pad, code), left, top - dp(5), text);
        c.drawText(Money.compact(min + pad, code), left, bottom - dp(5), text);
        c.drawText(FinanceUi.shortDate(LocalDate.ofEpochDay((long) x0)), left, bottom + dp(13), text);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(FinanceUi.shortDate(LocalDate.ofEpochDay((long) x1)), right, bottom + dp(13), text);
    }
}
