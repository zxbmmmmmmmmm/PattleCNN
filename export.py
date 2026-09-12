import onnx
import torch

from model import PattleCNN

model = PattleCNN()
model.load_state_dict(torch.load("model.pth", map_location="cpu", weights_only=True))
model.eval()

dummy_image = torch.randn(1, 3, 224, 224)
dummy_anchors = torch.rand(1, 4, 3)

torch.onnx.export(
    model,
    (dummy_image, dummy_anchors),
    "model.onnx",
    input_names=["image", "anchors"],
    output_names=["output"],
    opset_version=17,
    dynamo=False,
)

onnx.checker.check_model("model.onnx")