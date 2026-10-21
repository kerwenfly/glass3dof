package com.rxz.glass3dof;

/**
 * Mahony 互补滤波姿态解算，逐行移植自 imutool/glass/ahrs.go（同一台眼镜上验证过的实现）。
 *
 * 四元数分量顺序 (x, y, z, w)，语义为「机体系 → 世界系」：
 * 世界系 +Z 由加速度计定义（静止时测得的方向），因此 +Z 为「上」，
 * 机体系 +Z 是眼镜的朝向轴（yaw/pitch 的由来，与 headtrack 的 opentrack 模式一致）。
 *
 * ── 为什么去掉了 KI 积分项 ───────────────────────────────────────────────
 * 老实现带 KI=0.02 的误差积分。没有磁力计时，加速度只能观测「倾角」，
 * 误差向量在重力轴上的分量为 0；但积分项是按**机体轴**累加的：
 * 剧烈运动时线性加速度污染观测，误差向量在某个当时水平的轴上被卷进去，
 * 等头转过来，这个轴变成竖直，就会持续输出一个虚假的偏航角速度
 * —— 表现是"运动几次之后画面自己慢慢跑"。零偏现在由 {@link HeadPose}
 * 用「持续静止窗口」显式估计，因此这里只保留比例项（KP）修倾角。
 */
public final class Mahony {

    public final float[] q = {0f, 0f, 0f, 1f};
    /** 互补滤波比例增益（实机标定值，外部不应修改）。 */
    private static final float KP = 0.5f;

    private final float[] g = new float[3];
    private boolean started;

    public boolean started() {
        return started;
    }

    /** 用一次加速度读数把姿态对齐到「重力 = 世界 +Z」。 */
    public void resetToAccel(float[] accel) {
        invalidate();
        if (initFromAccel(accel)) started = true;
    }

    /** 丢弃当前姿态，等待下一帧加速度重新初始化。 */
    public void invalidate() {
        started = false;
        q[0] = 0f; q[1] = 0f; q[2] = 0f; q[3] = 1f;
    }

    /**
     * 推进一帧（默认信任加速度）。
     *
     * @param gyro  角速度，rad/s（已减零偏）
     * @param accel 加速度，m/s²（未归一化）
     * @param dt    采样间隔，秒
     */
    public void update(float[] gyro, float[] accel, float dt) {
        update(gyro, accel, dt, true);
    }

    /**
     * @param trustAccel 加速度是否可信。剧烈运动时线性加速度会污染倾角观测，
     *                   这一帧只做陀螺积分，不把运动加速度当成重力方向。
     */
    public void update(float[] gyro, float[] accel, float dt, boolean trustAccel) {
        if (!started) {
            if (initFromAccel(accel)) {
                started = true;
            }
            return;
        }
        float nf = norm3(accel);
        if (!trustAccel || nf < 1e-3f || dt <= 0f) {
            integrate(gyro, dt);
            return;
        }

        float qx = q[0], qy = q[1], qz = q[2], qw = q[3];
        // 估计重力方向（世界 +Z 在本体系中的表示）= 旋转矩阵第三行
        float vx = 2f * (qx * qz - qw * qy);
        float vy = 2f * (qw * qx + qy * qz);
        float vz = qw * qw - qx * qx - qy * qy + qz * qz;

        float ax = accel[0] / nf, ay = accel[1] / nf, az = accel[2] / nf;

        // 误差 = 实测重力方向 × 估计重力方向
        float ex = ay * vz - az * vy;
        float ey = az * vx - ax * vz;
        float ez = ax * vy - ay * vx;

        g[0] = gyro[0] + KP * ex;
        g[1] = gyro[1] + KP * ey;
        g[2] = gyro[2] + KP * ez;
        integrate(g, dt);
    }

    private void integrate(float[] gyro, float dt) {
        float qx = q[0], qy = q[1], qz = q[2], qw = q[3];
        float dx = 0.5f * (qw * gyro[0] + qy * gyro[2] - qz * gyro[1]);
        float dy = 0.5f * (qw * gyro[1] - qx * gyro[2] + qz * gyro[0]);
        float dz = 0.5f * (qw * gyro[2] + qx * gyro[1] - qy * gyro[0]);
        float dw = -0.5f * (qx * gyro[0] + qy * gyro[1] + qz * gyro[2]);

        float nx = qx + dx * dt, ny = qy + dy * dt, nz = qz + dz * dt, nw = qw + dw * dt;
        float n = (float) Math.sqrt(nx * nx + ny * ny + nz * nz + nw * nw);
        if (n < 1e-6f) {
            q[0] = 0f; q[1] = 0f; q[2] = 0f; q[3] = 1f;
            return;
        }
        q[0] = nx / n; q[1] = ny / n; q[2] = nz / n; q[3] = nw / n;
    }

    private boolean initFromAccel(float[] accel) {
        float n = norm3(accel);
        if (n < 1e-3f) {
            return false;
        }
        float ux = accel[0] / n, uy = accel[1] / n, uz = accel[2] / n;
        if (uz > 0.999999f) {
            q[0] = 0f; q[1] = 0f; q[2] = 0f; q[3] = 1f;
        } else if (uz < -0.999999f) {
            q[0] = 1f; q[1] = 0f; q[2] = 0f; q[3] = 0f;
        } else {
            // 旋转轴 = u × (0,0,1) = (uy, -ux, 0)
            float axisX = uy, axisY = -ux;
            float an = (float) Math.sqrt(axisX * axisX + axisY * axisY);
            float half = (float) Math.acos(uz) / 2f;
            float s = (float) Math.sin(half) / an;
            q[0] = axisX * s;
            q[1] = axisY * s;
            q[2] = 0f;
            q[3] = (float) Math.cos(half);
        }
        return true;
    }

    public static float norm3(float[] v) {
        return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }
}
