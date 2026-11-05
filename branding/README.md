# AirMirror 品牌资源

## 文件

| 文件 | 用途 |
| --- | --- |
| `logo.svg` | 矢量主标（512×512 viewBox，可直接编辑/缩放） |
| `logo-512.png` / `logo-192.png` / `logo-48.png` | 位图主标（透明背景） |
| `logo.ico` | Windows 图标（16/32/48/64/128/256 多尺寸） |
| `social-preview-1280x640.png` | GitHub 仓库 Social Preview |
| `app/src/main/res/mipmap-*/ic_launcher.png` | Android 启动图标（mdpi 48 → xxxhdpi 192） |

## 配色（与 `ui/Theme.kt` 同源）

- 背景 `#0B1F3A`、面板 `#12314F`、强调 `#17C3B2`、文字/目标屏 `#FFFFFF`。

## 构成与安全区

- 圆角方形底（圆角半径 22%），左侧青绿描边屏为「源」，右侧白色描边屏为「目标」，中间青绿箭头表示镜像投射。
- 图形主体落在画布 16%~84% 区间，四周至少 16% 留白；48px 下仍能分辨两个屏幕与箭头。
- 不依赖文字或字体，单色印刷时可退化为纯轮廓。

## 使用规范

- 图标直接引用 `@mipmap/ic_launcher`（已写入 AndroidManifest）。
- 社交预览图上传到 GitHub 仓库 Settings → Social preview。
- 禁止：拉伸变形、改配色、叠加文字、加阴影/外发光、把两个屏幕拆开单独使用。

## 重新生成

```powershell
python scripts/gen-branding.py   # 需 Pillow（仅开发机依赖，不进 APK）
```
