package com.uetty.common.tool.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.log4j.Logger;

/**
 * 待优化
 * @author Vince
 *
 */
public class FtpUtil {

	private static Logger logger = Logger.getLogger(FtpUtil.class);

	/**
	 * 获取ftp连接
	 */
	public static FTPClient connectFtp(String host, Integer port, String username, String password, String workDir) throws Exception {
		FTPClient ftp = new FTPClient();
		int reply;
		if (port == null) {
			port = 21;
		}
		ftp.connect(host, port);
		ftp.login(username, password);
		ftp.setFileType(FTPClient.BINARY_FILE_TYPE);
		ftp.enterLocalPassiveMode();
		ftp.setControlEncoding("UTF-8");
		reply = ftp.getReplyCode();
		if (!FTPReply.isPositiveCompletion(reply)) {
			ftp.disconnect();
			return null;
		}
		ftp.changeWorkingDirectory(workDir);
		return ftp;
	}

	/**
	 * 关闭ftp连接
	 */
	public static void closeFtp(FTPClient ftpClient) {
		if (ftpClient != null && ftpClient.isConnected()) {
			try {
				ftpClient.logout();
				ftpClient.disconnect();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public static String upload(FTPClient ftpClient, File f) throws Exception {
		String iden = UUID.randomUUID().toString();
		if (f.isDirectory()) {
			ftpClient.makeDirectory(f.getName());
			ftpClient.changeWorkingDirectory(f.getName());
			String[] files = f.list();
			for (String fstr : files) {
				File file1 = new File(f.getPath() + File.separator + fstr);
				if (file1.isDirectory()) {
					upload(ftpClient, file1);
					ftpClient.changeToParentDirectory();
				} else {
					File file2 = new File(f.getPath() + File.separator + fstr);
					FileInputStream input = new FileInputStream(file2);
					ftpClient.storeFile(file2.getName(), input);
					input.close();
				}
			}
		} else {
			FileInputStream input = new FileInputStream(f);
			boolean flag = ftpClient.storeFile(new String((iden + f.getName()).getBytes("UTF-8"), "iso-8859-1"), input);
			System.out.println("upload:" + f.getName() + "," + flag);
			input.close();
		}
		return iden + f.getName();
	}

	public static void startDown(String host, Integer port, String username, String password, String workDir, String localBaseDir) throws Exception {
		FTPClient ftpClient = null;
		if ((ftpClient = FtpUtil.connectFtp(host, port, username, password, workDir)) != null) {
			try {
				ftpClient.setControlEncoding("utf-8");
				FTPFile[] files = ftpClient.listFiles();
				for (int i = 0; i < files.length; i++) {
					try {
						downloadFile(ftpClient, files[i], localBaseDir, workDir);
					} catch (Exception e) {
						logger.error(e);
						logger.error("<" + files[i].getName() + ">下载失败");
					}
				}
			} catch (Exception e) {
				logger.error(e);
				logger.error("下载过程中出现异常");
			}
		} else {
			logger.error("链接失败！");
		}

	}

	private static void downloadFile(FTPClient ftpClient, FTPFile ftpFile, String relativeLocalPath, String relativeRemotePath) {
		if (ftpFile.isFile()) {
			if (ftpFile.getName().indexOf("?") == -1) {
				OutputStream outputStream = null;
				try {
					File locaFile = new File(relativeLocalPath + ftpFile.getName());
					// 判断文件是否存在，存在则返回
					if (locaFile.exists()) {
						return;
					} else {
						outputStream = new FileOutputStream(relativeLocalPath + ftpFile.getName());
						ftpClient.retrieveFile(ftpFile.getName(), outputStream);
						outputStream.flush();
						outputStream.close();
					}
				} catch (Exception e) {
					logger.error(e);
				} finally {
					try {
						if (outputStream != null) {
							outputStream.close();
						}
					} catch (IOException e) {
						logger.error("输出文件流异常");
					}
				}
			}
		} else {
			String newlocalRelatePath = relativeLocalPath + ftpFile.getName();
			String newRemote = new String(relativeRemotePath + ftpFile.getName().toString());
			File fl = new File(newlocalRelatePath);
			if (!fl.exists()) {
				fl.mkdirs();
			}
			try {
				newlocalRelatePath = newlocalRelatePath + File.separator;
				newRemote = newRemote + File.separator;
				String currentWorkDir = ftpFile.getName().toString();
				boolean changedir = ftpClient.changeWorkingDirectory(currentWorkDir);
				if (changedir) {
					FTPFile[] files = null;
					files = ftpClient.listFiles();
					for (int i = 0; i < files.length; i++) {
						downloadFile(ftpClient, files[i], newlocalRelatePath, newRemote);
					}
				}
				if (changedir) {
					ftpClient.changeToParentDirectory();
				}
			} catch (Exception e) {
				logger.error(e);
			}
		}
	}

	public static void downloadFileFromFtp(FTPClient ftpClient, String fileName, String relativeLocalPath, String relativeRemotePath) {
		if (fileName != null) {
			OutputStream outputStream = null;
			try {
				File locaFile = new File(relativeLocalPath + fileName);
				// 判断文件是否存在，存在则返回
				if (locaFile.exists()) {
					return;
				} else {
					outputStream = new FileOutputStream(relativeLocalPath + fileName);
					ftpClient.retrieveFile(fileName, outputStream);
					outputStream.flush();
					outputStream.close();
				}
			} catch (Exception e) {
				logger.error(e);
			} finally {
				try {
					if (outputStream != null) {
						outputStream.close();
					}
				} catch (IOException e) {
					logger.error("输出文件流异常");
				}
			}
		}
	}

	public static void main(String[] args) throws Exception {
	}

	public static boolean ftpUpload(FTPClient ftpClient, String path, Map<String, File> files, boolean clear) {
		System.out.println("ftpUpload");
		FileInputStream fis = null;
		try {
			if (ftpClient == null) {
				System.out.println("连接FTP失败!");
				return false;
			}
			System.out.println("path:" + path);
			File file = new File(path);
			File parent = file.getParentFile();
			if (parent != null && !parent.exists()) {
				parent.mkdirs();
			}
			file.createNewFile();

			System.out.println("FTP连接成功");
			ftpClient.setBufferSize(1024);
			ftpClient.setControlEncoding("UTF-8");
			ftpClient.setFileType(FTPClient.BINARY_FILE_TYPE);
			ftpClient.enterLocalPassiveMode();

			Set<String> keys = files.keySet();
			for (String key : keys) {
				fis = new FileInputStream(files.get(key));
				if (ftpClient.storeFile(key, fis)) {
					System.out.println("上传到FTP成功," + key);
				} else {
					System.out.println("上传到FTP失败," + key);
					return false;
				}
				if (fis != null)
					fis.close();
			}
			return true;
		} catch (Exception e) {
			System.out.println(e);
			return false;
		} finally {
			try {
				if (ftpClient.isConnected()) {
					ftpClient.disconnect();
				}
				if (fis != null) {
					fis.close();
				}
				// 清理临时文件
				if (clear && files.size() != 0) {
					for (String key : files.keySet()) {
						if (files.get(key).exists()) {
							files.get(key).delete();
						}
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

}
