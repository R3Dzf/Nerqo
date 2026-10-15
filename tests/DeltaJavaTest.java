import com.r3dzf.nerqo.DeltaPatch;
import java.io.File;
public class DeltaJavaTest {
    public static void main(String[] args) throws Exception {
        DeltaPatch.apply(new File(args[0]),new File(args[1]),new File(args[2]));
    }
}
