# Elite Tuner X 0.2.0

แอป Android สำหรับ Xiaomi Pad 8 Pro / Snapdragon 8 Elite (Adreno 830) แบบ **ไม่รูท**
วัดค่าจริง • HUD ทับเกม • เบนช์มาร์ก • ตรวจไดรเวอร์ Vulkan สำหรับ DXVK/VKD3D • จูนผ่าน Shizuku

## ติดตั้ง
1. ดาวน์โหลด APK จากหน้า **Releases** ของ repo (หรือ Actions → Elite Tuner X APK → artifact)
2. ติดตั้ง **Shizuku** แล้วเปิดผ่าน Wireless debugging (ต้องทำใหม่หลังรีบูต)
3. เปิด Elite Tuner X → กด "อนุญาตสิทธิ์ Shizuku" → อนุญาต "แสดงทับแอปอื่น" ตอนเปิด HUD

## ฟีเจอร์ และแหล่งข้อมูลจริง
| ฟีเจอร์ | ทำงานอย่างไร | ต้องใช้ Shizuku |
|---|---|---|
| FPS / Frame time | timestamp การแสดงภาพจริงของเลเยอร์เกมจาก SurfaceFlinger (`dumpsys SurfaceFlinger --latency`) — สำรองด้วย page-flip counter | ✔ |
| GPU load / clock | ตัวนับ kgsl ของ kernel (`gpu_busy_percentage`, `gpuclk`) | ✔ (ถ้าแอปอ่านตรงไม่ได้) |
| CPU load | `/proc/stat` | ✔ |
| CPU clock ต่อ cluster | `cpufreq/policy*/scaling_cur_freq` | อ่านตรงได้ส่วนใหญ่ |
| อุณหภูมิ CPU/GPU/ผิวเครื่อง | `/sys/class/thermal` + kgsl temp | ✔ (บางโซน) |
| วัตต์ | กระแส × แรงดันแบตเตอรี่ (ทั้งเครื่อง; ตอนชาร์จจะเป็นพลังงานขาเข้า) | ✘ |
| Thermal status / headroom | Android PowerManager | ✘ |
| HUD ทับเกม | overlay ลอย ลากได้ ย่อได้ ปุ่ม ● REC | ✘ (แต่ FPS ต้องใช้) |
| Session recorder | อัด frame time ทุกเฟรม + sensor ทุกวินาที → avg FPS, 1% low, 0.1% low, jank, stdev, FPS/W, กราฟ, export CSV, เทียบหลายเซสชัน | ✔ |
| GPU benchmark | OpenGL ES 3 offscreen 1080p ไม่ติด vsync: ALU + Fill/Texture + Stability (throttling %) | ✘ |
| Vulkan probe | อ่าน feature/extension/limit จริงจากไดรเวอร์ → ตาราง DXVK 1.10 / DXVK 2.x / D8VK / VKD3D-Proton | ✘ |
| Turnip / custom driver | นำเข้า .zip (AdrenoTools + meta.json) แล้วโหลดใน process แยกด้วย libadrenotools → probe + bandwidth bench เทียบกับไดรเวอร์ระบบ | ✘ |
| Vulkan bandwidth | vkCmdCopyBuffer / vkCmdFillBuffer บน VRAM | ✘ |
| Tuning | refresh-rate lock, animation scale, fixed performance mode, Game Mode + intervention (downscale/FPS cap), ล้าง RAM, ปิด phantom process killer, AOT compile, battery whitelist, ANGLE ต่อแอป, updatable driver ต่อแอป, thermal report, revert all | ✔ |

ทุกค่าที่แสดงจะมีแหล่งที่มากำกับ ถ้าเฟิร์มแวร์บล็อก sensor ตัวไหน จะแสดง **N/A** — ไม่มีการสุ่มหรือเดาค่า

## วิธีทดสอบ DXVK ทุกเวอร์ชันให้เห็นผลจริง
1. แท็บ **Drivers** → "ตรวจ Vulkan" ทั้งไดรเวอร์ระบบและ Turnip แต่ละตัว → ดูว่ารองรับ DXVK/VKD3D รุ่นไหน
2. แท็บ **Monitor** → ตั้งชื่อเซสชัน เช่น `GTA V • DXVK 2.3 • Turnip 25.1` → เปิด HUD
3. ใน Winlator/GameHub เลือก DXVK + ไดรเวอร์ → เข้าเกม → ไปจุดเดิม → กด ● REC 60–120 วินาที → ■
4. เปลี่ยน DXVK/ไดรเวอร์ แล้วทำซ้ำที่จุดเดิม
5. แท็บ **Sessions** → ติ๊กเลือกหลายเซสชัน → ตารางเทียบ (สีเขียว = ดีที่สุด)

## ข้อจำกัดที่เป็นเรื่องจริง (ไม่รูท)
- ล็อก CPU/GPU clock, voltage, governor **ไม่ได้** — Android ไม่ให้สิทธิ์นี้กับ ADB shell
- สลับไดรเวอร์ GPU ของระบบทั้งเครื่อง **ไม่ได้** — ได้เฉพาะต่อแอป ถ้าเฟิร์มแวร์มี Updatable Driver และ Turnip ภายใน Winlator/GameHub
- DXVK รันภายใน Wine ของ Winlator/GameHub แอปนี้จึงวัดผลจากภายนอกด้วย HUD + Session (ซึ่งเป็นวิธีที่ถูกต้องในการเทียบเวอร์ชัน)
- บาง sensor อาจถูก Xiaomi/HyperOS บล็อกแม้ผ่าน Shizuku — จะแสดง N/A พร้อมเหตุผล
- ผล Game Mode intervention ขึ้นกับว่าแอปเปิดรับหรือไม่ — ตรวจผลจริงได้จาก FPS ใน HUD

## Build
GitHub Actions (`.github/workflows/android.yml`) build release APK ทุกครั้งที่ push และสร้าง GitHub Release ให้อัตโนมัติ
Build เองในเครื่อง: `./gradlew assembleRelease` (ต้องมี Android SDK + NDK)

APK เซ็นด้วยคีย์ `app/etx-signing.jks` ที่ commit ไว้ เพื่อให้ติดตั้งทับเวอร์ชันเก่าได้ — คีย์นี้เป็นสาธารณะ ใช้กับแอป sideload ส่วนตัวเท่านั้น
