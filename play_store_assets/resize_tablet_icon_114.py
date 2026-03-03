from PIL import Image
import os

# Paths
input_path = r"d:\red social\play_store_assets\icono_play_store_512.png"
output_path = r"d:\red social\play_store_assets\icono_tablet_114.png"

def generate_tablet_icon():
    if not os.path.exists(input_path):
        print(f"Error: No se encontró el archivo base en {input_path}")
        return

    try:
        # Abrir la imagen original
        img = Image.open(input_path)
        
        # Redimensionar a 114x114 usando LANCZOS para mayor calidad
        # Mantenemos la transparencia si el original la tiene
        img_114 = img.resize((114, 114), Image.Resampling.LANCZOS)
        
        # Guardar en formato PNG
        img_114.save(output_path, "PNG")
        print(f"Se ha creado el icono de tablet: {output_path}")
    except Exception as e:
        print(f"Error al procesar la imagen: {e}")

if __name__ == "__main__":
    generate_tablet_icon()
