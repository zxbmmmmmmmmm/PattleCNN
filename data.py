"""Build the training set from album colour annotations in ``colors.csv``."""

from __future__ import annotations

import argparse
import csv
import unicodedata
from pathlib import Path
from typing import Iterable, Sequence

import numpy as np
import torch
from PIL import Image, ImageOps
from sklearn.cluster import KMeans
from tqdm import tqdm


DEFAULT_INPUT_DIR = Path("Albums")
DEFAULT_COLORS_CSV = Path("colors.csv")
DEFAULT_OUTPUT = Path("dataset.pt")
IMAGE_SIZE = (128, 128)
EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
# Backwards-compatible name for callers that used the old script constant.
INPUT_DIR = DEFAULT_INPUT_DIR


def _normalise_name(name: str) -> str:
  """Normalise names so Unicode variants and surrounding spaces match."""
  name = name.strip()
  # Do not use Path.stem unconditionally: album titles often contain dots
  # (for example ``APT.`` or ``vol.1``).  Strip only a recognised image
  # extension when one is actually present.
  suffix = Path(name).suffix.lower()
  if suffix in EXTENSIONS:
    name = name[:-len(suffix)]
  return unicodedata.normalize("NFC", name).casefold()


def _parse_hex_colour(value: str, *, row_number: int, column: str) -> list[float]:
  """Convert #RGB/#RRGGBB text to three floats in the range [0, 1]."""
  text = value.strip()
  if text.startswith("#"):
    text = text[1:]
  if len(text) == 3:
    text = "".join(channel * 2 for channel in text)
  if len(text) != 6:
    raise ValueError(
      f"Invalid colour {value!r} in row {row_number}, column {column!r}; "
      "expected #RGB or #RRGGBB."
    )
  try:
    channels = [int(text[offset:offset + 2], 16) for offset in (0, 2, 4)]
  except ValueError as exc:
    raise ValueError(
      f"Invalid colour {value!r} in row {row_number}, column {column!r}."
    ) from exc
  return [channel / 255.0 for channel in channels]


def extract_pattle(image: Image.Image, num_colors: int = 4) -> torch.Tensor:
  """Extract a fallback palette from an image with KMeans.

  The returned colours are ordered by perceived brightness, matching the
  ordering used by the original data-generation script.
  """
  pixels = np.asarray(image, dtype=np.float32).reshape(-1, 3) / 255.0
  kmeans = KMeans(n_clusters=num_colors, n_init=5, random_state=42).fit(pixels)
  colours = kmeans.cluster_centers_
  brightness = (
    colours[:, 0] * 0.2126
    + colours[:, 1] * 0.7152
    + colours[:, 2] * 0.0722
  )
  return torch.tensor(colours[np.argsort(brightness)], dtype=torch.float32)


def load_colour_labels(csv_path: Path) -> dict[str, torch.Tensor]:
  """Load ``album name -> (4, 3)`` colour tensors from ``colors.csv``."""
  csv_path = Path(csv_path)
  with csv_path.open("r", encoding="utf-8-sig", newline="") as handle:
    reader = csv.DictReader(handle)
    if not reader.fieldnames or len(reader.fieldnames) < 5:
      raise ValueError(
        f"{csv_path} must contain an album-name column and four colour columns."
      )
    name_column = reader.fieldnames[0]
    colour_columns = reader.fieldnames[1:5]
    labels: dict[str, torch.Tensor] = {}
    for row_number, row in enumerate(reader, start=2):
      raw_name = (row.get(name_column) or "").strip()
      if not raw_name:
        continue
      key = _normalise_name(raw_name)
      if key in labels:
        raise ValueError(f"Duplicate album name {raw_name!r} in row {row_number}.")
      colours = [
        _parse_hex_colour(row.get(column, ""), row_number=row_number, column=column)
        for column in colour_columns
      ]
      labels[key] = torch.tensor(colours, dtype=torch.float32)
  if not labels:
    raise ValueError(f"No colour annotations found in {csv_path}.")
  return labels


def _augmentations(image: Image.Image) -> Iterable[tuple[str, Image.Image]]:
  """Yield deterministic views of an image, retaining its colour label."""
  yield "original", image
  yield "rotate90", image.rotate(90, expand=False)
  yield "rotate180", image.rotate(180, expand=False)
  yield "rotate270", image.rotate(270, expand=False)
  yield "mirror", ImageOps.mirror(image)
  yield "flip", ImageOps.flip(image)


def _image_tensor(image: Image.Image) -> torch.Tensor:
  image_array = np.asarray(image, dtype=np.uint8).copy()
  return torch.from_numpy(image_array).permute(2, 0, 1)


def build_dataset(
  input_dir: Path = DEFAULT_INPUT_DIR,
  csv_path: Path = DEFAULT_COLORS_CSV,
  output_path: Path = DEFAULT_OUTPUT,
) -> tuple[int, int]:
  """Create and save the dataset; return ``(sample_count, image_count)``."""
  input_dir = Path(input_dir)
  csv_path = Path(csv_path)
  output_path = Path(output_path)
  if not input_dir.exists():
    raise FileNotFoundError(f"Image directory does not exist: {input_dir}")

  labels = load_colour_labels(csv_path)
  image_paths = sorted(
    path for path in input_dir.rglob("*")
    if path.is_file() and path.suffix.lower() in EXTENSIONS
  )
  images: list[torch.Tensor] = []
  pattles: list[torch.Tensor] = []
  filenames: list[str] = []
  matched_names: set[str] = set()
  kmeans_count = 0
  errors = 0

  for path in tqdm(image_paths, desc="Processing images"):
    key = _normalise_name(path.stem)
    try:
      with Image.open(path) as source:
        base = source.convert("RGB").resize(IMAGE_SIZE, Image.Resampling.LANCZOS)
        target = labels.get(key)
        if target is None:
          # Unannotated images are included once with a palette inferred from
          # the original image.  Do not augment these pseudo-labelled samples.
          variants = (("original", base),)
          target = extract_pattle(base)
        else:
          variants = _augmentations(base)
          matched_names.add(key)
        for variant, augmented in variants:
          images.append(_image_tensor(augmented))
          pattles.append(target.clone())
          filenames.append(
            path.name if variant == "original"
            else f"{path.stem}__{variant}{path.suffix}"
          )
        if key not in matched_names:
          kmeans_count += 1
    except Exception as exc:
      errors += 1
      print(f"Error processing {path}: {exc}")

  if not images:
    raise RuntimeError(
      f"No usable images found in {input_dir}; check the image files and {csv_path}."
    )
  output_path.parent.mkdir(parents=True, exist_ok=True)
  torch.save(
    {"images": torch.stack(images), "pattles": torch.stack(pattles), "filenames": filenames},
    output_path,
  )
  missing = len(labels) - len(matched_names)
  print(
    f"Saved {len(images)} samples from {len(matched_names)} CSV-labelled images "
    f"and {kmeans_count} KMeans-filled images to {output_path}."
  )
  if missing:
    print(f"Warning: {missing} CSV annotations have no matching image file.")
  if errors:
    print(f"Warning: {errors} image(s) could not be processed.")
  return len(images), len(matched_names)


def _parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--input-dir", type=Path, default=DEFAULT_INPUT_DIR)
  parser.add_argument("--colors-csv", type=Path, default=DEFAULT_COLORS_CSV)
  parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
  return parser.parse_args(argv)


if __name__ == "__main__":
  args = _parse_args()
  build_dataset(args.input_dir, args.colors_csv, args.output)
