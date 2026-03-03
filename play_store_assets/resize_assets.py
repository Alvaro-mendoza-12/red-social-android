from PIL import Image
import os

def resize_image(path, size):
    if not os.path.exists(path):
        print(f"File not found: {path}")
        return
    try:
        img = Image.open(path)
        resized_img = img.resize(size, Image.Resampling.LANCZOS)
        resized_img.save(path)
        print(f"Successfully resized {path} to {size}")
    except Exception as e:
        print(f"Error processing {path}: {e}")

# Assuming we run from within play_store_assets
resize_image("icono_play_store_512.png", (512, 512))
resize_image("grafico_funciones_1024x500.png", (1024, 500))
