import os
os.environ['KMP_DUPLICATE_LIB_OK'] = 'TRUE'

from torch.utils.data import DataLoader,TensorDataset
from model import PattleCNN, rgb_to_oklab
import torch.optim as optim
import torch
from tqdm import tqdm

data = torch.load("dataset.pt",weights_only=False)
if "anchors" not in data:
  raise RuntimeError(
    "dataset.pt 缺少 anchors；请先运行 python data.py 重新构建数据集。"
  )
dataset = TensorDataset(
  data["images"],
  data["pattles"],
  data["anchors"],
)

loader = DataLoader(
  dataset,
  batch_size=64,
  shuffle=True
)

device = torch.device(
    "cuda" if torch.cuda.is_available() else "cpu"
)

model = PattleCNN().to(device)
criterion = torch.nn.SmoothL1Loss(beta=0.02)

optimizer = optim.Adam(
  model.parameters(),
  lr = 0.001
)

epochs = 50

for epoch in tqdm(range(epochs)):
  model.train()
  total_loss = 0
  for images, pattles, anchors in loader:
    images = images.to(device) / 255.0
    pattles = pattles.to(device)
    anchors = anchors.to(device)
    outputs = model(images, anchors)

    predicted_oklab = rgb_to_oklab(outputs)
    target_oklab = rgb_to_oklab(pattles)
    oklab_loss = criterion(predicted_oklab, target_oklab)
    rgb_loss = torch.nn.functional.mse_loss(outputs, pattles)
    loss = oklab_loss + 0.05 * rgb_loss
    
    optimizer.zero_grad()
    loss.backward()
    optimizer.step()
    total_loss += loss.item()
  print(
        f"Epoch [{epoch + 1}/{epochs}], "
        f"Loss: {total_loss / len(loader):.4f}"
    )

torch.save(model.state_dict(),"model.pth")