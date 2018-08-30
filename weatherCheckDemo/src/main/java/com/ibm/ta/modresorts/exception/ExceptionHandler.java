package com.ibm.ta.modresorts.exception;

import org.apache.logging.log4j.Logger;

import javax.servlet.ServletException;

public class ExceptionHandler {
	
	public static void handleException(Exception e, String errorMsg, Logger logger) throws ServletException {
		if (e == null) {
			logger.error(errorMsg);
			throw new ServletException(errorMsg);
		}else {
			logger.error(errorMsg, e);
			throw new ServletException(errorMsg, e);
		}
	}
}

