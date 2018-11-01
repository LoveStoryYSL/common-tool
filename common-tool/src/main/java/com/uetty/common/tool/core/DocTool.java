package com.uetty.common.tool.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xwpf.converter.core.XWPFConverterException;
import org.apache.poi.xwpf.converter.pdf.PdfConverter;
import org.apache.poi.xwpf.converter.pdf.PdfOptions;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Image;
import com.itextpdf.text.pdf.PdfContentByte;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.PdfStamper;
import com.itextpdf.text.pdf.PdfWriter;
import com.uetty.common.tool.core.pdf.ExtFontRegistry;

import freemarker.template.TemplateException;

/**
 * 文档工具
 * 
 * @author vince
 *         <p>
 *         根据模板生成docx、docx转pdf、docx加密等
 */
public class DocTool {

	/**
	 * 生成docx文件
	 * <p>
	 * 技术原理依据：docx文件本身是zip格式打包的，将zip包内部文件制作为模板，填充数据后重新打包就能生成docx文件
	 * 
	 * @param docxFile
	 *            docx文件（作为样式模板）
	 * @param ftlFileMap
	 *            key(String): zip包内部相对根目录的路径， value(File): 制作的ftl文件
	 * @param dataMap
	 *            替换ftl模板标签的数据
	 */
	public static File createDocx(File docxFile, Map<String, File> ftlFileMap, Map<String, Object> dataMap)
			throws ZipException, IOException, TemplateException {

		Map<String, File> entryFileMap = new HashMap<String, File>();
		File outFile = null;
		ZipFile zipFile = null;
		ZipOutputStream zipout = null;

		try {
			// freemarker处理模板文件，生成临时文件
			for (String key : ftlFileMap.keySet()) {
				File ftlFile = ftlFileMap.get(key);
				String entryFilePath = FileTool.randomFilePathByExtName(null);
				File entryFile = FreemarkerEngine.process(dataMap, ftlFile.getAbsolutePath(), entryFilePath);
				entryFileMap.put(key, entryFile);
			}

			outFile = new File(FileTool.randomFilePathByExtName("docx"));

			zipFile = new ZipFile(docxFile);
			zipout = new ZipOutputStream(new FileOutputStream(outFile));

			// 将临时文件替换到docx文件下相应路径
			Enumeration<? extends ZipEntry> zipEntrys = zipFile.entries();
			while (zipEntrys.hasMoreElements()) {
				ZipEntry next = zipEntrys.nextElement();
				InputStream is = null;
				// 生成的zip包中添加文件项
				zipout.putNextEntry(new ZipEntry(next.toString()));
				// 尝试从生成的文件读取流
				File entryFile = entryFileMap.get(next.toString());
				if (entryFile != null) {
					is = new FileInputStream(entryFile);
				}
				// 从zip包中文件读取流
				if (is == null) {
					is = zipFile.getInputStream(next);
				}
				FileTool.writeFromInputStream(zipout, is);
			}
		} finally {
			for (File entryFile : entryFileMap.values()) {
				entryFile.delete();
			}
			if (zipFile != null) {
				zipFile.close();
			}
			if (zipout != null) {
				zipout.close();
			}
		}
		return outFile;
	}

	/**
	 * docx文件转pdf文件
	 */
	public static void docxConvertToPdf(File docxFile, File outFile) throws XWPFConverterException, IOException {
		InputStream source = new FileInputStream(docxFile);
		OutputStream target = new FileOutputStream(outFile);

		XWPFDocument doc = new XWPFDocument(source);
		// 输出设置
		PdfOptions options = PdfOptions.create();
		// 字体提供者
		ExtFontRegistry fontProvider = ExtFontRegistry.getRegistry();
		options.fontProvider(fontProvider);

		PdfConverter.getInstance().convert(doc, target, options);
	}

	/**
	 * 给docx文件添加open密码
	 */
	public static void docxAddPassword(File docxFile, File outFile, String password)
			throws InvalidFormatException, IOException, GeneralSecurityException {
		// 加密算法与加密密钥
		EncryptionInfo info = new EncryptionInfo(EncryptionMode.standard);
		Encryptor enc = info.getEncryptor();
		enc.confirmPassword(password);

		POIFSFileSystem fs = new POIFSFileSystem();
		// 获取输入到fs的输出流，并使用加密算法装饰输出流
		OutputStream os = enc.getDataStream(fs);

		// 读取docx文件，通过流输出到fs中
		OPCPackage opc = null;
		try {
			opc = OPCPackage.open(docxFile, PackageAccess.READ_WRITE);
			opc.save(os);
		} finally {
			if (opc != null) {
				opc.close();
			}
		}

		// 将fs中的数据写入到输出文件
		FileOutputStream fos = new FileOutputStream(outFile);
		fs.writeFilesystem(fos);
		fos.close();
	}

	/**
	 * pdf插入水印
	 */
	public static void insertWaterImage(PdfWriter writer, String inPdfPath, String outPdfPath, String imageLocalAddr)
			throws IOException, DocumentException {
		PdfReader reader = null;
		PdfStamper stamp = null;

		try {
			reader = new PdfReader(inPdfPath);
			// 假如PDF有4页，endPDFPage值为5
			int endPdfPage = reader.getNumberOfPages() + 1;

			File outParentFile = new File(outPdfPath).getParentFile();
			if (!outParentFile.exists()) {
				outParentFile.mkdirs();
			}
			stamp = new PdfStamper(reader, new FileOutputStream(new File(outPdfPath)));
			for (int i = 1; i < endPdfPage; i++) {
				PdfContentByte under = stamp.getUnderContent(i);
				// 插入另一组水印
				Image img = Image.getInstance(imageLocalAddr);
				// 设置图片缩放比例
				img.scalePercent(78);
				// 设置图片绝对宽度
				img.scaleAbsoluteWidth(596);
				// 设置图片绝对位置
				img.setAbsolutePosition(0, 0);
				under.addImage(img);
			}
		} finally {
			if (stamp != null) {
				stamp.close();
			}
			if (reader != null) {
				reader.close();
			}
		}
	}

	/**
	 * 按指定的表头和数据，在指定的路径生成excel文件
	 * 
	 * @Title: exportExcel
	 * @param path
	 *            导出路径
	 * @param sheetTitle
	 *            Excel的sheet标题
	 * @param headMap
	 *            表格第一行表头,类型为LinkedHashMap
	 * @param dataList
	 *            对应的数据
	 * @return 是否导出成功
	 * @throws Exception
	 * @throws @author
	 *             Mirror 2012-10-8
	 */
	public static boolean exportExcel(String path, String sheetTitle, LinkedHashMap<String, String> headMap,
			List<Object> dataList) throws Exception {
		Set<String> keySet = headMap.keySet();
		Iterator<String> iter = keySet.iterator();
		List<String> values = new ArrayList<String>();
		List<String> keys = new ArrayList<String>();
		while (iter.hasNext()) {
			String key = iter.next();
			values.add(headMap.get(key));
			keys.add(key);
		}
		HSSFWorkbook wb = new HSSFWorkbook();
		HSSFSheet sheet = wb.createSheet(sheetTitle);
		HSSFCellStyle style = wb.createCellStyle();
		style.setAlignment(HSSFCellStyle.ALIGN_CENTER);
		// 根据表头设置列宽
		for (int i = 0; i < values.size(); i++) {
			sheet.setColumnWidth(i, (values.get(i).length() + 2) * 2 * 256);
		}
		HSSFRow row = null;
		row = sheet.createRow((int) 0);
		HSSFCell cell = null;
		// 根据map创建表头
		for (int i = 0; i < values.size(); i++) {
			cell = row.createCell(i);
			cell.setCellValue(values.get(i));
			cell.setCellStyle(style);
		}
		// 写数据
		if (null != dataList) {
			for (int i = 0; i < dataList.size(); i++) {
				row = sheet.createRow((int) i + 1);
				Object gift = dataList.get(i);
				for (int j = 0; j < keys.size(); j++) {

					cell = row.createCell(j);
					String s = String.valueOf(PropertyUtils.getProperty(gift, keys.get(j)));
					if (s == null || s.equals("null")) {
						cell.setCellValue("");
					} else {
						cell.setCellValue(s);
					}
				}
			}
		}
		writeExcelFile(wb, path, sheetTitle + ".xls");
		return true;
	};

	/**
	 * 文件写出
	 * 
	 * @Title: writeExcelFile
	 * @param wb
	 * @param path
	 * @param fileName
	 * @return
	 * @throws @author
	 *             Mirror 2012-10-8
	 */
	public static boolean writeExcelFile(HSSFWorkbook wb, String path, String fileName) {
		FileOutputStream fout = null;
		try {
			fout = new FileOutputStream(path + fileName);
			wb.write(fout);
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
			return false;
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		} finally {
			if (fout != null) {
				try {
					fout.close();
				} catch (IOException e) {
					e.printStackTrace();
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * 如果文件存在，则删除子文件，不存在则新建
	 * 
	 * @Title: deleteExistFiles
	 * @param dir
	 * @throws @author
	 *             Mirror 2012-10-8
	 */
	public static void deleteExistFiles(String dir) {
		File files = new File(dir);
		// 删除之前下载过的文件，文件夹不存在时自动新建
		if (!files.exists()) {
			files.mkdirs();
		} else {
			File[] dirFile = files.listFiles();
			for (int i = 0; i < dirFile.length; i++) {
				// 删除子文件
				dirFile[i].delete();
			}
		}
	}

	/**
	 * 删除指定的文件
	 * 
	 * @param dir
	 */
	public static void deleteExistFile(String dir) {
		File files = new File(dir);
		if (files.exists()) {
			files.delete();
		}

	}

	/**
	 * 写出客户端下载
	 * 
	 * @Title: outputExcel
	 * @param os
	 * @param file
	 * @throws UnsupportedEncodingException
	 * @throws @author
	 *             Mirror 2012-10-8
	 */
	public static void outputExcel(OutputStream os, File file) throws UnsupportedEncodingException {
		int fileLength = (int) file.length();
		// 如果文件长度大于0
		if (fileLength != 0) {
			// 创建输入流
			InputStream inStream = null;
			byte[] buf = new byte[4096];
			// 创建输出流
//			ServletOutputStream servletOS = null;
			try {
				inStream = new FileInputStream(file);
				int readLength;
				while (((readLength = inStream.read(buf)) != -1)) {
					os.write(buf, 0, readLength);
				}
				os.flush();
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e1) {
				e1.printStackTrace();
			} catch (java.lang.IllegalStateException e3) {
				e3.printStackTrace();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
}
