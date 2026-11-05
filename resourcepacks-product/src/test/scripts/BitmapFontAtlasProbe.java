import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.minecraft.client.renderer.texture.DynamicAtlasTree;
public final class BitmapFontAtlasProbe {
 public static void main(String[] args) throws Exception {
  var image=ImageIO.read(Path.of(args[0]).toFile());
  int size=Integer.parseInt(args[1]);
  int columns=args.length>2?Integer.parseInt(args[2]):1;
  if(columns<1||image.getWidth()%columns!=0)throw new IllegalArgumentException("Invalid bitmap columns");
  int width=image.getWidth()/columns;
  var atlas=new DynamicAtlasTree(0,0,size,size);
  if(atlas.insert(width,image.getHeight(),1)==null)
   throw new IllegalStateException("Bitmap cell "+width+"x"+image.getHeight()+" cannot fit native "+size+"x"+size+" font atlas");
  System.out.println("nativeMinecraftAtlas=ok bitmap="+image.getWidth()+"x"+image.getHeight()+" atlas="+size+"x"+size+" cells="+columns);
 }
}
