import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.minecraft.client.renderer.texture.DynamicAtlasTree;
public final class BitmapFontAtlasProbe {
 public static void main(String[] args) throws Exception {
  var image=ImageIO.read(Path.of(args[0]).toFile());
  int size=Integer.parseInt(args[1]);
  var atlas=new DynamicAtlasTree(0,0,size,size);
  if(atlas.insert(image.getWidth(),image.getHeight(),1)==null)
   throw new IllegalStateException("Bitmap "+image.getWidth()+"x"+image.getHeight()+" cannot fit native "+size+"x"+size+" font atlas");
  System.out.println("nativeMinecraftAtlas=ok bitmap="+image.getWidth()+"x"+image.getHeight()+" atlas="+size+"x"+size);
 }
}
