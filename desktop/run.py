"""Source entry point; the frozen executable uses this same module."""
from pathlib import Path
import sys

if not getattr(sys, "frozen", False):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

if __name__ == "__main__":
    try:
        from ouc_desktop.app import main
        result = main()
    except Exception:
        if "--smoke-test" in sys.argv and "--smoke-output" in sys.argv:
            import traceback
            output = Path(sys.argv[sys.argv.index("--smoke-output") + 1])
            output.mkdir(parents=True, exist_ok=True)
            (output / "crash.txt").write_text(traceback.format_exc(), encoding="utf-8")
            result = 4
        else:
            raise
    raise SystemExit(result)
