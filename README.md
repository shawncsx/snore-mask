# SnoreMask v1.1 — 枕边手机加速度计自适应掩蔽声

**零本地环境编译**：把这个文件夹推到 GitHub，Actions 自动产出 `app-debug.apk`，手机浏览器下载安装即可。

---

## 🚀 一键上手（无需电脑配 SDK）

### 1. 新建 GitHub 仓库并推送
```bash
# 在本地解压/克隆后的文件夹内执行
git init
git add .
git commit -m "v1.1-mvp"

# 方式 A：用 GitHub CLI（推荐，自动建仓库并推送）
gh repo create snore-mask --public --source=. --push

# 方式 B：网页手动建仓库后
git remote add origin https://github.com/<你的用户名>/snore-mask.git
git branch -M main
git push -u origin main
```

### 2. 等待云端编译
- 打开仓库 **Actions** 标签页
- 看到 `Build Debug APK` 绿色通过（约 2~3 分钟）
- 点击该运行记录 → **Artifacts** → 下载 `app-debug-apk.zip`

### 3. 手机安装
- 手机浏览器打开下载链接，或传到微信/Telegram 点击安装
- 首次安装需允许「安装未知应用」权限

---

## 📱 今晚使用流程

1. **插电**、**拆手机壳**
2. 手机**裸机底部/背部紧贴床板/床腿/床头柜靠床边缘**（参考方案文档「三贴准则」）
3. 打开 App → 点「开始屏蔽」
4. 允许 **体感传感器**、**通知** 权限
5. 通知栏出现「校准中 10 秒」→ **保持不动**
6. **校准通过**：通知变「运行中」，扬声器发出低沉嗡嗡声 → 躺下睡觉
7. **校准失败**：通知提示「床架不传振，方案无效」→ 改用耳塞/白噪声

---

## 📂 关键文件清单（已全部生成）

```
SnoreMask/
├── .github/workflows/android.yml   # 云编译脚本
├── app/
│   ├── build.gradle.kts            # 模块配置
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/example/snoremask/
│   │   │   ├── MainActivity.kt
│   │   │   ├── MaskingService.kt
│   │   │   ├── RingBuffer.kt
│   │   │   ├── Preprocess.kt
│   │   │   ├── YinDetector.kt
│   │   │   ├── CombSynthesizer.kt
│   │   │   └── Agc.kt
│   │   └── res/layout/activity_main.xml
├── build.gradle.kts                # 项目级配置
├── settings.gradle.kts
└── README.md
```

---

## ⚙️ 核心参数（如需微调只改这里）

| 文件 | 关键常量 | 含义 |
|------|----------|------|
| `CombSynthesizer.kt` | `weight` 表、`maxHarmonics` | 虚拟低音增强权重、谐波阶数 |
| `Agc.kt` | `attackMs=200`、`releaseMs=2000` | 自适应增益攻击/释放时间 |
| `MaskingService.kt` | `peak>0.6 && harmonicRatio>0.4` | 校准通过阈值 |
| `Preprocess.kt` | `HP_FC=10Hz`、`LP_FC=300Hz` | 加速度计带通滤波器 |

---

## 🐛 常见问题

| 现象 | 排查 |
|------|------|
| Actions 红了 | 点开日志搜 `FAILED`，通常是 Gradle 缓存miss，再跑一次即可 |
| 校准总失败 | 换贴**床腿金属螺丝/床头柜靠墙实木板**位置，裸机贴紧 |
| 声音太大/太小 | 改 `Agc.kt` 里 `gain.coerceIn(0.0, 4.0)` 上限，或 `hardLimit` 0.89→0.7 |
| 手机发烫 | 正常（持续 DSP+扬声器），充电器≥10W、背部贴金属床架散热更好 |

---

## 📄 许可证
MIT — 随意改、随意用、随意传。  
核心算法来源：本项目设计文档 v1.1（加速度计参考 + 梳状掩蔽 + 心理声学增强 + 硬校准）。

---

**搞定。** 现在把这整个文件夹推到 GitHub，喝杯水回来下载 APK 就能用。