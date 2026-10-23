"""Export launcher PNG densities from the original imagegen master (Qt only)."""
from pathlib import Path
from PySide6.QtCore import Qt
from PySide6.QtGui import QImage

ROOT = Path(__file__).resolve().parent
RES = ROOT.parents[1] / "android" / "app" / "src" / "main" / "res"


def main():
    image = QImage(str(ROOT / "ounet-mobile.png"))
    if image.isNull():
        raise RuntimeError("Missing OUNet mobile icon master")
    # Android applies its own launcher mask. Keep a full-bleed square master.
    drawable = RES / "drawable-nodpi"
    drawable.mkdir(exist_ok=True)
    if not image.save(str(drawable / "ounet_launcher.png")):
        raise RuntimeError("Cannot export adaptive launcher source")
    for density, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        # Android minSdk 28 always uses adaptive icons. Keep density exports as
        # distributable design files, without redundant legacy launcher resources.
        folder = ROOT / "android-png" / density
        folder.mkdir(parents=True, exist_ok=True)
        scaled = image.scaled(size, size, Qt.AspectRatioMode.IgnoreAspectRatio, Qt.TransformationMode.SmoothTransformation)
        for name in ("ic_launcher.png",):
            if not scaled.save(str(folder / name)):
                raise RuntimeError(f"Cannot export {folder / name}")
    print("OUNet Android launcher assets exported.")


if __name__ == "__main__":
    main()
