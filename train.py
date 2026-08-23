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
criterion = torch.nn.MSELoss()

optimizer = optim.Adam(
  model.parameters(),
  lr = 0.001
)

epochs = 50

for epoch in tqdm(range(epochs)):
  model.train()
  total_loss = 0
  for images, pattles in loader:
    images = images.to(device) / 255.0
    pattles = pattles.to(device)
    outputs = model(images)
    loss = criterion(outputs,pattles)
    optimizer.zero_grad()
    loss.backward()
    optimizer.step()
    total_loss += loss.item()
  print(
        f"Epoch [{epoch + 1}/{epochs}], "
        f"Loss: {total_loss / len(loader):.4f}"
    )

torch.save(model.state_dict(),"model.pth")