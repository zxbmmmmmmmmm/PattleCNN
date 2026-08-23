import torch
from sklearn.cluster import KMeans
from PIL import Image
import numpy as np
from pathlib import Path
from tqdm import tqdm

INPUT_DIR = Path("dataset")
EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


images = []
pattles = []
filenames = []

def extract_pattle(image, num_colors = 4):
  pixels = np.asarray(image, dtype=np.float32).reshape(-1, 3) / 255.0
  kmeans = KMeans(n_clusters=num_colors,n_init=5,random_state=42).fit(pixels)
  colors = kmeans.cluster_centers_
  brightness = (
    colors[:, 0] * 0.2126 +
    colors[:, 1] * 0.7152 +
    colors[:, 2] * 0.0722
  )
  order = np.argsort(brightness)
  colors = colors[order]
  return torch.tensor(colors, dtype=torch.float32)


image_paths = sorted(
    path
    for path in INPUT_DIR.iterdir()
    if path.is_file() and path.suffix.lower() in EXTENSIONS)

for path in tqdm(image_paths):
  try:
    with Image.open(path) as image:
      image = image.convert("RGB")
      image = image.resize((128,128),Image.Resampling.LANCZOS)
      image_array = np.asarray(image,dtype = np.uint8).copy()
      image_tensor = torch.from_numpy(image_array).permute(2,0,1)
      pattle = extract_pattle(image)
      images.append(image_tensor)
      pattles.append(pattle)
      filenames.append(path.name)
  except Exception as e:
    print(f"Error processing {path}: {e}")


torch.save({
  "images": torch.stack(images),
  "pattles": torch.stack(pattles),
  "filenames":filenames},"dataset.pt"
)
