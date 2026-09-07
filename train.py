import os
os.environ['KMP_DUPLICATE_LIB_OK'] = 'TRUE'

from torch.utils.data import DataLoader,TensorDataset
from model import PattleCNN
import torch.optim as optim
import torch
from tqdm import tqdm

data = torch.load("dataset.pt",weights_only=False)
dataset = TensorDataset(
  data["images"],
  data["pattles"],
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

def rgb_to_oklab(rgb: torch.Tensor) -> torch.Tensor:
  linear_rgb = torch.where(
    rgb <= 0.04045,
    rgb / 12.92,
    ((rgb + 0.055) / 1.055).pow(2.4),
  )

  r, g, b = linear_rgb.unbind(dim=-1)
  l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
  m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
  s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b

  l, m, s = l.clamp_min(0).pow(1 / 3), m.clamp_min(0).pow(1 / 3), s.clamp_min(0).pow(1 / 3)

  return torch.stack((
    0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
  ), dim=-1)

for epoch in tqdm(range(epochs)):
  model.train()
  total_loss = 0
  for images, pattles in loader:
    images = images.to(device) / 255.0
    pattles = pattles.to(device)
    outputs = model(images)

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