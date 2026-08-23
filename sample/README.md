# KMeans / CNN 取色对比工具

这是一个本地运行的可视化页面。拖入图片后，程序会分别用 KMeans 和项目根目录中的 `model.pth` 提取 4 个代表色，同时显示色板量化预览、HEX/RGB 色值和单次推理耗时。

## 运行

在项目根目录执行：

```powershell
python -m pip install -r sample/requirements.txt
python sample/app.py
```

程序默认打开 `http://127.0.0.1:7860`。停止程序时，在终端按 `Ctrl+C`。

如不希望自动打开浏览器：

```powershell
python sample/app.py --no-browser
```

如需指定端口：

```powershell
python sample/app.py --port 8000
```

## 对比口径

- 两种方法都使用与训练代码一致的 128×128 RGB 输入。
- KMeans 使用 4 个聚类、`n_init=5`、`random_state=42`，与 `data.py` 保持一致。
- 两组颜色都按感知亮度从暗到亮排列。
- 量化预览会把每个像素替换为对应色板中欧氏距离最近的颜色，仅用于直观比较色板覆盖效果。
