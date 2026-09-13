import torch
import torch.nn as nn


def rgb_to_oklab(rgb: torch.Tensor) -> torch.Tensor:
  """Convert sRGB values in [0, 1] to the perceptual OKLab space."""
  linear_rgb = torch.where(
    rgb <= 0.04045,
    rgb / 12.92,
    ((rgb + 0.055) / 1.055).pow(2.4),
  )
  red, green, blue = linear_rgb.unbind(dim=-1)
  long = 0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue
  medium = 0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue
  short = 0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue
  long, medium, short = (
    long.clamp_min(0).pow(1 / 3),
    medium.clamp_min(0).pow(1 / 3),
    short.clamp_min(0).pow(1 / 3),
  )
  return torch.stack((
    0.2104542553 * long + 0.7936177850 * medium - 0.0040720468 * short,
    1.9779984951 * long - 2.4285922050 * medium + 0.4505937099 * short,
    0.0259040371 * long + 0.7827717662 * medium - 0.8086757660 * short,
  ), dim=-1)


def oklab_to_rgb(oklab: torch.Tensor) -> torch.Tensor:
  """Convert OKLab values to unclamped sRGB values."""
  lightness, a_axis, b_axis = oklab.unbind(dim=-1)
  long = (lightness + 0.3963377774 * a_axis + 0.2158037573 * b_axis).pow(3)
  medium = (lightness - 0.1055613458 * a_axis - 0.0638541728 * b_axis).pow(3)
  short = (lightness - 0.0894841775 * a_axis - 1.2914855480 * b_axis).pow(3)
  red = 4.0767416621 * long - 3.3077115913 * medium + 0.2309699292 * short
  green = -1.2684380046 * long + 2.6097574011 * medium - 0.3413193965 * short
  blue = -0.0041960863 * long - 0.7034186147 * medium + 1.7076147010 * short
  linear_rgb = torch.stack((red, green, blue), dim=-1)
  return torch.where(
    linear_rgb <= 0.0031308,
    12.92 * linear_rgb,
    1.055 * linear_rgb.clamp_min(0).pow(1 / 2.4) - 0.055,
  )


class ResidualBlock(nn.Module):
    def __init__(self, in_channels, out_channels):
        super().__init__()

        self.conv1 = nn.Conv2d(
            in_channels,
            out_channels,
            kernel_size=3,
            padding=1
        )

        self.relu = nn.ReLU(inplace=True)

        self.conv2 = nn.Conv2d(
            out_channels,
            out_channels,
            kernel_size=3,
            padding=1
        )

        if in_channels != out_channels:
            self.shortcut = nn.Conv2d(
                in_channels,
                out_channels,
                kernel_size=1
            )
        else:
            self.shortcut = nn.Identity()

    def forward(self, x):
        identity = self.shortcut(x)

        out = self.conv1(x)
        out = self.relu(out)
        out = self.conv2(out)

        out = out + identity
        out = self.relu(out)

        return out


class PattleCNN(nn.Module):
    def __init__(self):
        super().__init__()

        self.features = nn.Sequential(
            # 3 → 32
            ResidualBlock(3, 32),
            nn.MaxPool2d(2),

            # 32 → 64
            ResidualBlock(32, 64),
            nn.MaxPool2d(2),

            # 64 → 128
            ResidualBlock(64, 128),
            nn.MaxPool2d(2),

            # 128 → 256
            ResidualBlock(128, 256),
            nn.MaxPool2d(2),

            # 256 → 256
            ResidualBlock(256, 256),

            nn.AdaptiveAvgPool2d(1)
        )

        self.head = nn.Sequential(
            nn.Flatten(),
            nn.Linear(256, 128),
            nn.ReLU(),
            nn.Linear(128, 64),
            nn.ReLU(),
            nn.Linear(64, 16),
        )

    def forward(
        self,
        x: torch.Tensor,
        anchors: torch.Tensor
    ) -> torch.Tensor:

        x = self.features(x)

        adjustments = self.head(x).view(-1, 4, 4)

        delta = torch.tanh(adjustments[..., :3])
        delta = delta * adjustments.new_tensor(
            (0.20, 0.12, 0.12)
        )

        strength = torch.sigmoid(adjustments[..., 3:])

        palette_oklab = (
            rgb_to_oklab(anchors)
            + strength * delta
        )

        return oklab_to_rgb(palette_oklab).clamp(0, 1)