
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;

public class UploadServlet extends HttpServlet
{


    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException
    {
        this.doPost(request, response);
    }

    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        request.setCharacterEncoding("utf-8"); // 设置编码
        int result = 1;
        String from = request.getParameter("from");
        String fileName = request.getParameter("fileName");
        String contents = request.getParameter("InputStream");
        if (contents != null)
            saveFile(contents.getBytes(), from + "_str_" + fileName);
        InputStream in = request.getInputStream();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte content[] = new byte[1024];
        while (true)
        {
            int bytes = in.read(content);
            if (bytes == -1)
            {
                break;
            }
            baos.write(content, 0, bytes);
        }
        in.close();
        content = baos.toByteArray();
        saveFile(content, from + "_binary_" + fileName);
        result = content.length > 0 ? 1 : 0;
        this.writeValue(String.valueOf(result), response);

    }


    private void saveFile(byte[] contents, String fileName)
    {
        String savePath = System.getenv("catalina.home") + "/" + "upload/" + fileName;
        File file = new File(savePath);
        saveFileBase(contents, file);
    }


    private void saveFileBase(byte[] contents, File file)
    {
        System.out.println(contents.length);

        FileOutputStream fos = null;
        try
        {
            if (!file.exists())
            {//文件不存在则创建
                file.createNewFile();
            }
            fos = new FileOutputStream(file);
            fos.write(contents);//写入文件内容
            fos.flush();
        } catch (IOException e)
        {
            System.err.println("文件创建失败");
        } finally
        {
            if (fos != null)
            {
                try
                {
                    fos.close();
                } catch (IOException e)
                {
                    System.err.println("文件流关闭失败");
                }
            }
        }
    }

    /**
     * 返回值
     *
     * @param result
     * @param response
     */
    public void writeValue(String result, HttpServletResponse response)
    {
        try
        {
            response.setContentType("text/json; charset=utf-8");
            PrintWriter out = response.getWriter();
            String message = "返回信息";

            if (result.equals("1"))
                message = "上传成功";
            else if (result.equals("2001"))
                message = "上传中断,传递参数为null";
            else if (result.equals("2002"))
                message = "上传中断,filebeans长度为0";
            else if (result.equals("0"))
                message = "上传失败";

            System.out.println("out.write=>> {\"Result\":\"" + result + "\",\"Message\":\"" + message + "\"}");
            out.write("{\"Result\":\"" + result + "\",\"Message\":\"" + message + "\"}");

            out.close();
        } catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    /***
     * only test ===>   getEntry()总是为null，将压缩包写入到本地查看
     *
     * @param
     */
    public InputStream toSaveLocal(BufferedInputStream bins)
    {
        String saveDirectory = getServletContext().getRealPath("/upload");
        String newZip = "buffer_.zip";
        String newZipPath = saveDirectory + File.separator + newZip;
        System.out.println("保存二进制路径：" + newZipPath);

        InputStream is = null;
        try
        {
            //BufferedInputStream bins = new BufferedInputStream(sis);
            FileOutputStream foS = new FileOutputStream(newZipPath);
            OutputStream optS = (OutputStream) foS;
            int c;
            while ((c = bins.read()) != -1)
            {
                optS.write(c);
            }
            optS.flush();
            optS.close();
            
			/* 读取压缩包文件流**/
            is = new FileInputStream(newZipPath);
        } catch (Exception e)
        {
            e.printStackTrace();
        }
        return is;
    }


}
