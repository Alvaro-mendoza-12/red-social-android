from PIL import Image, ImageDraw, ImageFont
import os

def create_mockup(filename, title_text, sub_text, bg_color="#6A1B9A", text_color="white"):
    # Target size for Amazon Tablet
    width, height = 1920, 1080
    
    # Create background
    img = Image.new('RGB', (width, height), color=bg_color)
    draw = ImageDraw.Draw(img)
    
    # Draw simple design elements (gradients/patterns)
    # Let's draw some faint circles for a modern look
    draw.ellipse([width*0.7, -100, width*1.2, 400], fill="#7B1FA2")
    draw.ellipse([-200, height*0.6, 500, height*1.1], fill="#8E24AA")
    
    # Draw a simulated tablet frame (outline)
    frame_padding = 100
    draw.rectangle([frame_padding, frame_padding, width-frame_padding, height-frame_padding], 
                   outline="#E1BEE7", width=5)
    
    # In a real scenario, we would use a TTF font. 
    # Since we might not have one, we use default or simple shapes to simulate text if needed.
    # But Pillow usually has a basic font.
    try:
        # Try to find a system font or use default
        font_title = ImageFont.load_default(size=100)
        font_sub = ImageFont.load_default(size=50)
    except:
        font_title = ImageFont.load_default()
        font_sub = ImageFont.load_default()

    # Draw Text
    draw.text((width/2, height/2 - 50), title_text, fill=text_color, anchor="mm", font=font_title)
    draw.text((width/2, height/2 + 100), sub_text, fill=text_color, anchor="mm", font=font_sub)
    
    # Save image
    output_path = os.path.join(r"d:\red social\play_store_assets", filename)
    img.save(output_path)
    print(f"Created {output_path}")

# Create the 3 assets
create_mockup("tablet_1_social.png", "Conectate", "Tu red social, tus comunidades", bg_color="#4A148C")
create_mockup("tablet_2_chat.png", "Chat y Grupos", "Habla con tus amigos en tiempo real", bg_color="#311B92")
create_mockup("tablet_3_challenges.png", "Retos Diarios", "Participa y vota por el mejor talento", bg_color="#1A237E")
