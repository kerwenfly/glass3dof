package com.rxz.glass3dof;

import android.opengl.Matrix;

/**
 * 姿态会话：在线零偏估计 → Mahony 解算 → 中位比较 → 逐轴取反 → 滤波 → 相机视图矩阵。
 *
 * ── 这一版针对"反复运动后画面会自己跑"重做了零偏与冻结逻辑 ──────────────
 *
 * 漂移的三个真实来源（都是实现问题，不是 IMU 的物理极限）：
 *
 *  1. **慢速平移被当成零偏学走**。老版只要瞬时角速度 < 2.9°/s 就立刻用该样本更新
 *     零偏——缓慢转头时陀螺读数同样很小，零偏把这段真实运动吸收进去；
 *     手一停，姿态就以学到的那个速率反向匀速跑。
 *     现在要求：**持续静止 ≥ 0.25s** 且**陀螺方差很小**（抖动而非匀速运动）才学分，
 *     并且零偏每秒钟允许的变化量有上限（起步 0.5 rad/s²、稳定后 0.03 rad/s²）。
 *
 *  2. **Mahony 积分项卷绕**。见 {@link Mahony} 的注释，KI 已置 0，零偏只在这里估。
 *
 *  3. **停下后滤波器还在爬**。One Euro 有惯性，冻结只停了陀螺积分，输出仍在收敛
 *     到新值，肉眼就是"停住了画面又滑几度"。现在冻结期间直接**latch 输出**
 *     （保持上一帧的视图矩阵），恢复运动时滤波从 latch 值继续，无跳变。
 *
 * 物理上限：眼镜没有磁力计/相机，**偏航角没有绝对参考**，只能靠零偏精度 + 手动复位。
 * 想再上一层：换 ESKF（误差状态卡尔曼，把零偏建进状态量），但那也只能把偏航漂移
 * 压到"零偏估计精度"这一量级，不能给出绝对朝向。
 *
 * 线程：IMU 线程调 {@link #onSample}（~460Hz），GL 线程调 {@link #getViewMatrix}（每帧），
 * 界面线程调 {@link #recenter} 等。全部由 sLock 串行化。
 */
public final class HeadPose {

    /** 进入"静止"的陀螺阈值：约 2.9°/s（用于冻结画面，太松会卡住慢速转头）。 */
    private static final float STILL_RAD = 0.05f;
    /** 退出"静止"的阈值（迟滞）：约 2°/s，慢速平移也要解冻，否则画面会卡住。 */
    private static final float STILL_EXIT_RAD = 0.035f;
    /**
     * **零偏学习**的陀螺阈值：约 0.86°/s，贴近噪声底。
     * 老版用 2.9°/s，导致 1–2°/s 的慢速平移被当成"静止"学进零偏，
     * 手一停画面就按学到的速率反向匀速跑——这是"运动几次后画面自己动"的主因。
     */
    private static final float BIAS_STILL_RAD = 0.015f;
    /** 零偏学习还要求陀螺窗口方差足够小：std < 0.01 rad/s（约 0.57°/s）。 */
    private static final float STILL_VAR_MAX = 0.01f * 0.01f;
    /** 持续静止多久才认为"这是真的静止"：0.25s。 */
    private static final float STILL_TIME_S = 0.25f;
    /** 冻结门槛：静止 0.3s 后屏蔽陀螺积分。 */
    private static final float FREEZE_TIME_S = 0.30f;

    /** 陀螺统计量的 EMA 系数（约 0.1s 窗口 @460Hz）。 */
    private static final float GYRO_STAT_ALPHA = 0.02f;
    /** 零偏跟随系数（仅在通过静止判定的样本上生效）。 */
    private static final float BIAS_ALPHA = 0.05f;
    /** 零偏变化速率上限：起步阶段放宽，稳定后收紧（rad/s 每秒）。 */
    private static final float BIAS_RATE_FAST = 0.50f;
    private static final float BIAS_RATE_SLOW = 0.03f;
    /** 快速收敛阶段：累计"可学静止"满这么久之前用大速率（覆盖种子取自运动帧的误差）。 */
    private static final float FAST_LEARN_S = 1.0f;

    /** 加速度模长参考的 EMA 系数，只在小角速度时更新，避免运动加速度污染。 */
    private static final float ACC_NORM_ALPHA = 0.02f;
    /** 判定"加速度可信"的相对带宽：偏离参考 15% 以内才用加速度修倾角。 */
    private static final float ACC_TRUST_BAND = 0.15f;

    private final Object sLock = new Object();

    private final Mahony ahrs = new Mahony();
    private final float[] bias = new float[3];
    /** 陀螺 EMA 均值与平方均值（用于估方差：慢速平移 vs 真静止）。 */
    private final float[] gMean = new float[3];
    private final float[] gMean2 = new float[3];

    private final float[] upBody = {0f, 1f, 0f};
    private boolean seeded;
    private float stillTime;
    private float accNormRef;
    /** 累计已学习的静止时长（决定用快速还是慢速跟随零偏）。 */
    private float learnedTime;
    /**
     * 静止窗口内的残差（|ω − 零偏|）EMA，单位 °/s。
     * 这才是"还会不会漂"的指标：零偏估计收敛后它应接近噪声底。
     */
    private float residualDps;
    /** 界面线程经 isFrozen() 读取，volatile 保证可见性。 */
    private volatile boolean frozen;

    /** 中位基准（场景坐标系三轴在世界系中的表示）。 */
    private final float[] f0 = {0f, 0f, 1f};
    private final float[] u0 = {0f, 1f, 0f};
    private final float[] r0 = {1f, 0f, 0f};
    private boolean recentered;

    // ---- 用户开关（界面线程写，其余线程读）
    /** 前后取反：IMU 的 +Z 若指向人脸，勾上让相机看向屏幕。 */
    public volatile boolean invertForward = false;
    public volatile boolean invertYaw = false;
    public volatile boolean invertPitch = false;
    public volatile boolean invertRoll = false;
    /** 静止时冻结输出（抑制 yaw 漂移）。 */
    public volatile boolean freezeWhenStill = true;

    private final OneEuro fYaw = new OneEuro(1.2f, 0.06f);
    private final OneEuro fPitch = new OneEuro(1.2f, 0.06f);
    private final OneEuro fRoll = new OneEuro(1.2f, 0.06f);
    private long lastFilterNs;

    /** 冻结期间直接输出这一帧（latch），避免"停下后还滑几度"。 */
    private final float[] latchedView = new float[16];
    private boolean latched;

    /** 最近一次输出的角度（度），供界面显示。 */
    private volatile float outYaw, outPitch, outRoll;
    public volatile long frames;
    public volatile float hz;

    private long hzWindowStart;
    private int hzCount;

    private final float[] R = new float[9];
    private final float[] forward = new float[3];
    private final float[] up = new float[3];
    private final float[] gyroUsed = new float[3];
    private final float[] view = new float[16];

    // ---------------------------------------------------------------- 输入

    /**
     * @param accel   加速度 m/s²（设备原始）
     * @param gyroRad 角速度 rad/s（设备原始，未减零偏）
     * @param dt      采样间隔（秒，来自设备时间戳）
     */
    public void onSample(float[] accel, float[] gyroRad, float dt) {
        synchronized (sLock) {
            frames++;
            tickHz();
            if (!(dt > 0f)) {
                dt = 1f / 460f;
            }

            // 第一帧建立种子：零偏=当前角速度，"上"=当前加速度方向。随后立即开始跟踪。
            if (!seeded) {
                System.arraycopy(gyroRad, 0, bias, 0, 3);
                System.arraycopy(gyroRad, 0, gMean, 0, 3);
                for (int i = 0; i < 3; i++) {
                    gMean2[i] = gyroRad[i] * gyroRad[i];
                }
                copyNormalized(accel, upBody);
                accNormRef = Mahony.norm3(accel);
                seeded = true;
                ahrs.resetToAccel(accel);
                learnedTime = 0f;
            }

            // 陀螺统计量（均值 / 平方均值 → 方差）
            for (int i = 0; i < 3; i++) {
                gMean[i] += GYRO_STAT_ALPHA * (gyroRad[i] - gMean[i]);
                gMean2[i] += GYRO_STAT_ALPHA * (gyroRad[i] * gyroRad[i] - gMean2[i]);
            }

            // 判定"静止/可学零偏"要看**减掉当前零偏估计之后**的残差：
            // 否则种子偏大时残差永远超过阈值，零偏就再也学不进去了（自锁）
            float resX = gyroRad[0] - bias[0];
            float resY = gyroRad[1] - bias[1];
            float resZ = gyroRad[2] - bias[2];
            float resMag = (float) Math.sqrt(resX * resX + resY * resY + resZ * resZ);

            boolean gyroQuiet = resMag < STILL_RAD;
            boolean gyroSteady = true;
            for (int i = 0; i < 3; i++) {
                float var = gMean2[i] - gMean[i] * gMean[i];
                if (var > STILL_VAR_MAX) {
                    gyroSteady = false;
                    break;
                }
            }
            boolean still = gyroQuiet && gyroSteady;

            float aNorm = Mahony.norm3(accel);
            if (resMag < STILL_EXIT_RAD && aNorm > 1e-3f) {
                accNormRef += ACC_NORM_ALPHA * (aNorm - accNormRef);
            }

            if (still) {
                stillTime += dt;
                // "上"方向顺势平滑，抵抗手抖（只在静止时更新）
                for (int i = 0; i < 3; i++) {
                    upBody[i] += 0.05f * (accel[i] - upBody[i]);
                }
                normalize(upBody);
            } else {
                stillTime = 0f;
            }

            // 零偏只在「真正静止」时学习：贴近噪声底 + 陀螺方差小 + 持续 0.25s，
            // 并且限制每秒变化量。冻结（画面保持）仍用较松的 2.9°/s 阈值。
            boolean biasLearnable = resMag < BIAS_STILL_RAD && gyroSteady
                    && stillTime >= STILL_TIME_S;
            if (biasLearnable) {
                float rate = learnedTime < FAST_LEARN_S ? BIAS_RATE_FAST : BIAS_RATE_SLOW;
                float maxStep = rate * dt;
                learnedTime += dt;
                float resNow = (float) Math.toDegrees(resMag);
                residualDps = residualDps <= 0f ? resNow : residualDps + 0.02f * (resNow - residualDps);
                for (int i = 0; i < 3; i++) {
                    float step = BIAS_ALPHA * (gMean[i] - bias[i]);
                    if (step > maxStep) {
                        step = maxStep;
                    } else if (step < -maxStep) {
                        step = -maxStep;
                    }
                    bias[i] += step;
                }
            }

            // 冻结（带迟滞）：静止够久就停陀螺积分；要明显动起来才解冻
            if (freezeWhenStill && stillTime > FREEZE_TIME_S) {
                frozen = true;
            } else if (frozen && resMag > STILL_EXIT_RAD) {
                frozen = false;
            }

            if (frozen) {
                gyroUsed[0] = 0f;
                gyroUsed[1] = 0f;
                gyroUsed[2] = 0f;
            } else {
                gyroUsed[0] = gyroRad[0] - bias[0];
                gyroUsed[1] = gyroRad[1] - bias[1];
                gyroUsed[2] = gyroRad[2] - bias[2];
            }

            // 运动加速度会污染倾角观测：偏离 1g 太多就先只积分
            boolean trustAccel = accNormRef <= 1e-3f
                    || Math.abs(aNorm - accNormRef) <= ACC_TRUST_BAND * accNormRef;
            ahrs.update(gyroUsed, accel, dt, trustAccel);

            if (!recentered) {
                recenterLocked();
            }
        }
    }

    private void tickHz() {
        long now = System.nanoTime();
        if (hzWindowStart == 0L) {
            hzWindowStart = now;
        }
        if (++hzCount >= 64) {
            hz = hzCount * 1_000_000_000f / (now - hzWindowStart);
            hzWindowStart = now;
            hzCount = 0;
        }
    }

    // ---------------------------------------------------------------- 复位

    /** 把当前朝向设为中位（画面拉回正前方）。 */
    public void recenter() {
        synchronized (sLock) {
            recenterLocked();
        }
    }

    /** 重新采样零偏（不用静止等待，收敛在 0.5 秒内自动完成）；不动画面中位。 */
    public void resetBias() {
        synchronized (sLock) {
            seeded = false;
            stillTime = 0f;
            learnedTime = 0f;
            residualDps = 0f;
            frozen = false;
        }
    }

    /** 会话开始/结束：连中位一起清掉，下一帧重新定中。 */
    public void resetSession() {
        synchronized (sLock) {
            seeded = false;
            recentered = false;
            stillTime = 0f;
            frozen = false;
            learnedTime = 0f;
            residualDps = 0f;
            accNormRef = 0f;
            latched = false;
            outYaw = outPitch = outRoll = 0f;
            lastFilterNs = 0L;
            fYaw.reset();
            fPitch.reset();
            fRoll.reset();
        }
    }

    private void recenterLocked() {
        if (!ahrs.started()) {
            return;
        }
        buildRotation(ahrs.q, R);
        readForwardUp();

        // 正交化，保证 lookAt 的基可用
        normalize(forward);
        float d = dot(up, forward);
        up[0] -= d * forward[0];
        up[1] -= d * forward[1];
        up[2] -= d * forward[2];
        normalize(up);

        System.arraycopy(forward, 0, f0, 0, 3);
        System.arraycopy(up, 0, u0, 0, 3);
        cross(f0, u0, r0);   // 屏幕右方向（相机朝 +Z 看时即场景 -X）
        normalize(r0);

        recentered = true;
        stillTime = 0f;
        frozen = false;
        latched = false;
        lastFilterNs = 0L;
        fYaw.reset();
        fPitch.reset();
        fRoll.reset();
        outYaw = outPitch = outRoll = 0f;
    }

    private void readForwardUp() {
        forward[0] = R[2];
        forward[1] = R[5];
        forward[2] = R[8];
        if (invertForward) {
            forward[0] = -forward[0];
            forward[1] = -forward[1];
            forward[2] = -forward[2];
        }
        up[0] = R[0] * upBody[0] + R[1] * upBody[1] + R[2] * upBody[2];
        up[1] = R[3] * upBody[0] + R[4] * upBody[1] + R[5] * upBody[2];
        up[2] = R[6] * upBody[0] + R[7] * upBody[1] + R[8] * upBody[2];
    }

    // ---------------------------------------------------------------- 输出

    /**
     * 相机视图矩阵（column-major 4x4）。
     *
     * @return false 表示尚未复位（此时输出单位矩阵，画面居中）
     */
    public boolean getViewMatrix(float[] out) {
        synchronized (sLock) {
            if (!recentered || !ahrs.started()) {
                Matrix.setIdentityM(out, 0);
                return false;
            }
            // 冻结期间直接复用上一帧（latch），彻底消除"停下后画面还滑一段"
            if (frozen && latched) {
                System.arraycopy(latchedView, 0, out, 0, 16);
                return true;
            }

            buildRotation(ahrs.q, R);
            readForwardUp();

            // 投影到中位场景坐标系
            float fx = dot(forward, r0), fy = dot(forward, u0), fz = dot(forward, f0);
            float ux = dot(up, r0), uy = dot(up, u0), uz = dot(up, f0);

            // 朝向 → yaw/pitch，翻滚由"上"方向的倾斜单独算
            float yaw = (float) Math.toDegrees(Math.atan2(fx, fz));
            float pitch = (float) Math.toDegrees(Math.asin(clamp(fy, -1f, 1f)));
            float roll = rollFrom(ux, uy, uz, yaw, pitch);

            // 渲染线程滤波：One Euro，快转时几乎不延迟、静止时压掉抖动
            long now = System.nanoTime();
            float dt = lastFilterNs == 0L ? 0.016f : (now - lastFilterNs) / 1e9f;
            lastFilterNs = now;
            dt = clamp(dt, 0.004f, 0.05f);
            yaw = fYaw.filter(yaw, dt);
            pitch = fPitch.filter(pitch, dt);
            roll = fRoll.filter(roll, dt);

            if (invertYaw) {
                yaw = -yaw;
            }
            if (invertPitch) {
                pitch = -pitch;
            }
            if (invertRoll) {
                roll = -roll;
            }
            outYaw = yaw;
            outPitch = pitch;
            outRoll = roll;

            // 由角度重建朝向与上方向
            double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch), rr = Math.toRadians(roll);
            float cfx = (float) (Math.sin(ry) * Math.cos(rp));
            float cfy = (float) Math.sin(rp);
            float cfz = (float) (Math.cos(ry) * Math.cos(rp));
            float rx = (float) Math.cos(ry), rz = (float) -Math.sin(ry);

            // up0 = forward × right（yaw/pitch 对应的"无翻滚"上方向）
            float u0x = cfy * rz - cfz * 0f;
            float u0y = cfz * rx - cfx * rz;
            float u0z = cfx * 0f - cfy * rx;
            // 绕 forward 转 roll
            float kx = cfy * u0z - cfz * u0y;
            float ky = cfz * u0x - cfx * u0z;
            float kz = cfx * u0y - cfy * u0x;
            float cr = (float) Math.cos(rr), sr = (float) Math.sin(rr);
            float nux = u0x * cr + kx * sr;
            float nuy = u0y * cr + ky * sr;
            float nuz = u0z * cr + kz * sr;

            Matrix.setLookAtM(view, 0,
                    0f, 0f, 0f,
                    cfx, cfy, cfz,
                    nux, nuy, nuz);
            System.arraycopy(view, 0, out, 0, 16);
            System.arraycopy(view, 0, latchedView, 0, 16);
            latched = true;
            return true;
        }
    }

    /**
     * 翻滚：实际"上"方向相对 yaw/pitch 推出的"无翻滚上方向"绕视线轴的转角（度）。
     */
    private static float rollFrom(float ux, float uy, float uz, float yawDeg, float pitchDeg) {
        double ry = Math.toRadians(yawDeg), rp = Math.toRadians(pitchDeg);
        float fx = (float) (Math.sin(ry) * Math.cos(rp));
        float fy = (float) Math.sin(rp);
        float fz = (float) (Math.cos(ry) * Math.cos(rp));
        float rx = (float) Math.cos(ry), rz = (float) -Math.sin(ry);

        // up0 = forward × right，其中 right = (cos yaw, 0, -sin yaw)
        float u0x = fy * rz;
        float u0y = fz * rx - fx * rz;
        float u0z = -fy * rx;
        float n = (float) Math.sqrt(u0x * u0x + u0y * u0y + u0z * u0z);
        if (n < 1e-6f) {
            return 0f;
        }
        u0x /= n; u0y /= n; u0z /= n;

        float dotp = ux * u0x + uy * u0y + uz * u0z;
        // cross(up0, up) · forward
        float cx = u0y * uz - u0z * uy;
        float cy = u0z * ux - u0x * uz;
        float cz = u0x * uy - u0y * ux;
        float sinp = cx * fx + cy * fy + cz * fz;
        return (float) Math.toDegrees(Math.atan2(sinp, dotp));
    }

    // ---------------------------------------------------------------- 状态

    public float[] yawPitch() {
        return new float[]{outYaw, outPitch};
    }

    public float rollDeg() {
        return outRoll;
    }

    public boolean isFrozen() {
        return frozen;
    }

    /**
     * 当前**估计零偏**（三轴绝对值最大，°/s）。
     * 注意：这是 IMU 自身的零速偏移量，大不代表在漂——它正是被减掉的那部分。
     */
    public float biasDps() {
        synchronized (sLock) {
            float m = Math.abs(bias[0]);
            m = Math.max(m, Math.abs(bias[1]));
            m = Math.max(m, Math.abs(bias[2]));
            return (float) Math.toDegrees(m);
        }
    }

    /** 静止窗口内的残差（°/s）：越小说明零偏估得越准、画面越不会自己跑。 */
    public float residualDps() {
        synchronized (sLock) {
            return residualDps;
        }
    }

    public boolean isTracking() {
        synchronized (sLock) {
            return recentered && ahrs.started();
        }
    }

    // ---------------------------------------------------------------- 数学

    /** 四元数 (x,y,z,w) → 3x3 行主序旋转矩阵（机体系 → 世界系）。 */
    static void buildRotation(float[] q, float[] m) {
        float x = q[0], y = q[1], z = q[2], w = q[3];
        m[0] = 1f - 2f * (y * y + z * z);
        m[1] = 2f * (x * y - z * w);
        m[2] = 2f * (x * z + y * w);
        m[3] = 2f * (x * y + z * w);
        m[4] = 1f - 2f * (x * x + z * z);
        m[5] = 2f * (y * z - x * w);
        m[6] = 2f * (x * z - y * w);
        m[7] = 2f * (y * z + x * w);
        m[8] = 1f - 2f * (x * x + y * y);
    }

    private static void copyNormalized(float[] src, float[] dst) {
        System.arraycopy(src, 0, dst, 0, 3);
        normalize(dst);
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static void cross(float[] a, float[] b, float[] out) {
        float x = a[1] * b[2] - a[2] * b[1];
        float y = a[2] * b[0] - a[0] * b[2];
        float z = a[0] * b[1] - a[1] * b[0];
        out[0] = x; out[1] = y; out[2] = z;
    }

    private static void normalize(float[] v) {
        float n = Mahony.norm3(v);
        if (n < 1e-6f) {
            return;
        }
        v[0] /= n; v[1] /= n; v[2] /= n;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * One Euro 滤波：截止频率随速度自适应。
     * 慢速时截止频率低（抖动被压掉），快速时升高（几乎不拖影）。
     * 参考 Casiez et al. 2012，这里只做一维、只用于角度。
     */
    static final class OneEuro {
        private final float minCutoff;
        private final float beta;
        private final float dCutoff = 1.0f;
        private float xPrev;
        private float dxPrev;
        private boolean init;

        OneEuro(float minCutoff, float beta) {
            this.minCutoff = minCutoff;
            this.beta = beta;
        }

        void reset() {
            init = false;
        }

        float filter(float x, float dt) {
            if (!init) {
                init = true;
                xPrev = x;
                dxPrev = 0f;
                return x;
            }
            float dx = (x - xPrev) / dt;
            float edx = lowPass(dx, dxPrev, alpha(dCutoff, dt));
            dxPrev = edx;
            float cutoff = minCutoff + beta * Math.abs(edx);
            float y = lowPass(x, xPrev, alpha(cutoff, dt));
            xPrev = y;
            return y;
        }

        private static float alpha(float cutoff, float dt) {
            float tau = 1f / (2f * (float) Math.PI * cutoff);
            return 1f / (1f + tau / dt);
        }

        private static float lowPass(float x, float prev, float a) {
            return a * x + (1f - a) * prev;
        }
    }
}
