package com.ibm.watson;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.ErrorHandler;
import org.json.*;

import com.ibm.ta.modresorts.WeatherServlet;
import com.ibm.ta.modresorts.exception.ExceptionHandler;

import javax.net.ssl.HostnameVerifier;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ProcessPolicyServ extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    final static Logger logger = LogManager.getLogger(ProcessPolicyServ.class);


    // location to store file uploaded
    private static final String UPLOAD_DIRECTORY = "./";

    // upload settings
    private static final int MEMORY_THRESHOLD = 1024 * 1024 * 3;  // 3MB
    private static final int MAX_FILE_SIZE = 1024 * 1024 * 40; // 40MB
    private static final int MAX_REQUEST_SIZE = 1024 * 1024 * 50; // 50MB

    private static final int CONNECTION_TIMEOUT = 20000;
    private static final int CONNECT_REQUEST_TIMEOUT = 20000;
    private static final int SOCKET_TIMEOUT = 60000;

    private static final String BACKUP_DATA_FILE = "/BackupData.json";

    private static final String API_PATH = "/api/v1/";
    private static final String API_PARSE = "parse?analyze=true&version=2018-02-23";

    private static final String ENV_WATSON_SVC_NAME = "WATSON_CNC_SVC_NAME";
    private static final String ENV_WATSON_SVC_PORT = "WATSON_CNC_SVC_PORT";
    /*
        Environment vars to be set for containers:

          env:
          - name: WATSON_CNC_SVC_NAME
            value: cnc-latest-ibm-watson-compare-comply-prod-frontend.default
          - name: WATSON_CNC_SVC_PORT
            value: "9443"
     */


    /**
     * constructor
     */
    public ProcessPolicyServ() {
        super();
    }

    /**
     * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse
     * response)
     */
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        // checks if the request actually contains upload file
        if (!ServletFileUpload.isMultipartContent(request)) {
            // if not, we stop here
            PrintWriter writer = null;
            try{
            	writer = response.getWriter();
            	String errorMsg = "Error: Form must has enctype=multipart/form-data.";
            	logger.error(errorMsg);
    			writer.println(errorMsg);
                writer.flush();
                return;
            }finally {           
            	writer.close();
            }
           
        }

        // configures upload settings
        DiskFileItemFactory factory = new DiskFileItemFactory();
        // sets memory threshold - beyond which files are stored in disk
        factory.setSizeThreshold(MEMORY_THRESHOLD);
        // sets temporary location to store files
        factory.setRepository(new File(System.getProperty("java.io.tmpdir")));

        ServletFileUpload upload = new ServletFileUpload(factory);

        // sets maximum size of upload file
        upload.setFileSizeMax(MAX_FILE_SIZE);

        // sets maximum size of request (include file + form data)
        upload.setSizeMax(MAX_REQUEST_SIZE);

        // creates the directory if it does not exist
        File uploadDir = new File(UPLOAD_DIRECTORY);
        if (!uploadDir.exists()) {
            uploadDir.mkdir();
        }

        try {
            // parses the request's content to extract file data
            @SuppressWarnings("unchecked")
            List<FileItem> formItems = upload.parseRequest(request);

            if (formItems != null && formItems.size() > 0) {
                // iterates over form's fields
                for (FileItem item : formItems) {
                    // processes only fields that are not form fields
                    if (!item.isFormField()) {
                        String fileName = new File(item.getName()).getName();
                        if (fileName == null || fileName.trim().length() == 0) {
                        	String errorMsg = "Insurance file is not uploaded";
                        	ExceptionHandler.handleException(null, errorMsg, logger);
                        }
                        String filePath = UPLOAD_DIRECTORY + File.separator + fileName;
                        File storeFile = new File(filePath);

                        // saves the file on disk
                        item.write(storeFile);
                        JSONObject excls = analyzeFile(filePath);

                        logger.debug("RESULTS::::\n" + excls.toString());

                        request.setAttribute("message",
                                "Upload has been done successfully!");

                        response.setContentType("text/html");
                        PrintWriter out = response.getWriter();

                        out.println("<html>");
                        out.println("<head>");
                        out.println("<title>Insurance Cover Check</title>");
                        out.println("<script>");
                        out.println("function getExclusions() {");
                        out.println("    return " + excls.toString());
                        out.println(" }\n");
                        out.println("</script>");
                        out.println("</head>");
                        out.println("<body>");
                        out.println("<p>No file uploaded</p>");
                        out.println("</body>");
                        out.println("</html>");
                        out.flush();

                    }
                }
            }
        } catch (Exception ex) {
            String errorMsg = "Exception uploading file: " + ex.getMessage();
            ExceptionHandler.handleException(ex, errorMsg, logger);

        }
    }

    private JSONObject analyzeFile(String fileName) throws IOException {

        logger.debug("Entering analyzeFile.............");

        String cncURI;

        String cncService = System.getenv(ENV_WATSON_SVC_NAME);
        String cncPort = System.getenv(ENV_WATSON_SVC_PORT);

        if (cncService == null || cncPort == null) {
            logger.info("No service name and/or port defined for Watson Compare and Comply Service. Set " +
                    ENV_WATSON_SVC_NAME + " " + ENV_WATSON_SVC_PORT +
                    " environment vars for the container");
            logger.info("Falling back to backup data...");
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (Exception e) {
            	// ignore the exception, only write into log
            	logger.warn("Thread sleep was interupted", e);
            }
            return getExclusions(getBackupJSON().toString());
        } 
            
        cncURI = "https://" + cncService + ":" + cncPort + API_PATH + API_PARSE;
        logger.info("Watson Compare and Comply URI constructed from environment variable is: " + cncURI);
        
        try {
            RequestConfig requestConfig = RequestConfig.copy(RequestConfig.DEFAULT)
                    /*.setProxy(new HttpHost("localhost", 8888))// for debug*/
                    .setConnectionRequestTimeout(CONNECT_REQUEST_TIMEOUT)
                    .setConnectTimeout(CONNECTION_TIMEOUT)
                    .setSocketTimeout(SOCKET_TIMEOUT)
                    .build();

            SSLContextBuilder builder = new SSLContextBuilder();
            builder.loadTrustMaterial(null, new TrustSelfSignedStrategy());
            HostnameVerifier allowAllHosts = new NoopHostnameVerifier();
            SSLConnectionSocketFactory connectionFactory = new SSLConnectionSocketFactory(builder.build(), allowAllHosts);
            CloseableHttpClient httpclient = HttpClients.custom().setSSLSocketFactory(
                    connectionFactory)
                    .setDefaultRequestConfig(requestConfig)
                    .build();

            File file = new File(fileName);
            FileBody fileBody = new FileBody(file, ContentType.DEFAULT_BINARY);

            MultipartEntityBuilder ebuilder = MultipartEntityBuilder.create();
            ebuilder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);
            ebuilder.addPart("file", fileBody);
            HttpEntity entity = ebuilder.build();

            HttpPost post = new HttpPost(cncURI);

            post.setEntity(entity);

            HttpResponse response = httpclient.execute(post);

            if (response != null) {
                logger.debug(response.getStatusLine());
                //logger.debug(response);
                HttpEntity responseEntity = response.getEntity();
                if (responseEntity != null) {
                    // Read the response string if required
                    InputStream responseStream = null;
                    BufferedReader br = null;
                    try {
                    	responseStream = responseEntity.getContent();
                    	if (responseStream != null) {
                    		br = new BufferedReader(new InputStreamReader(responseStream));
                    		String responseLine = br.readLine();
                    		String tempResponseString = "";
                    		while (responseLine != null) {
                    			tempResponseString = tempResponseString + responseLine + System.getProperty("line.separator");
                    			responseLine = br.readLine();
                    		}

                    		if (tempResponseString.length() > 0) {
                    			logger.debug("tempResponseString: " + tempResponseString);
                    			return getExclusions(tempResponseString);
                    		}
                    	}
                    } finally {
                    	if (br != null) {
                    		br.close();
                    	}
                    	if (responseStream != null) {
                    		responseStream.close();
                    	}
                    }
                }
            }

        } catch (Exception ex) {
            logger.warn("error happened getting data from Watson API", ex);

            //If exception for any reason here - fall back to backupjson data
            logger.info("Falling back to backup data...");
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (Exception e) {
            	// ignore the exception, only write into log
            	logger.warn("Thread sleep was interupted", e);
            }
            return getExclusions(getBackupJSON().toString());
        }

        logger.debug("Leaving analyzeFile...");

        return new JSONObject();
    }

    /*
     * Iterate through JSON and pick out the exclusions
     */
    private JSONObject getExclusions(String json) {

        JSONObject exclusions = new JSONObject();
        JSONArray exclArray = new JSONArray();

        try {
            JSONObject obj = new JSONObject(json);

            JSONArray elements = obj.getJSONArray("elements");

            for (int i = 0; i < elements.length(); i++) {
                JSONArray types = elements.getJSONObject(i).getJSONArray("types");
                logger.debug(types.length());

                if (types.length() > 0) {
                	logger.debug(types);

                    for (int j = 0; j < types.length(); j++) {

                        String nature = types.getJSONObject(j).getJSONObject("label").getString("nature");
                        logger.debug("nature: " + nature);

                        if (nature.toString().equals("Exclusion")) {
                            String text = elements.getJSONObject(i).getString("sentence_text");
                            exclArray.put(text);
                            logger.debug(text);
                        }
                    }
                }
            }

            exclusions.put("exclusions", exclArray);

            return exclusions;

        } catch (Exception e) {
        	// ignore the exception, only write into log
        	logger.warn("error happened during getExclusions call, return nothing ", e);
            return new JSONObject();
        }

    }

    /*
     * Get backup json data if query to watson does not work
     */
    private JSONObject getBackupJSON() throws IOException {

        InputStream stream = null;
        try {

            stream = ProcessPolicyServ.class.getResourceAsStream(BACKUP_DATA_FILE);
            String content = IOUtils.toString(stream, "UTF-8");

            logger.debug("content: " + content);

            // Convert JSON string to JSONObject
            return new JSONObject(content);

        } catch (Exception e) {
            // If fail to read the file for any reason - then just get the backup json object in this file.
            logger.warn("Failed with json file, using embedded json data", e);
 
            try {
                return new JSONObject(BACKUP_DATA_P1 + BACKUP_DATA_P2);
            } catch (Exception ex) {
                logger.error("Failed with embedded json");
                return new JSONObject();
            }
        }finally {
        	if (stream != null) {
        		stream.close();
        	}
        }
    }


    private static String BACKUP_DATA_P1 = "{\n" +
            "  \"document_title\" : \"ACME_Policy.pdf\",\n" +
            "  \"document_text\" : \"No Document Text\",\n" +
            "  \"elements\" : [ {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 5784,\n" +
            "      \"end\" : 6694\n" +
            "    },\n" +
            "    \"sentence_text\" : \"We wish to bring to your attention some of the important features of your travel insurance Policy.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 8128,\n" +
            "      \"end\" : 9399\n" +
            "    },\n" +
            "    \"sentence_text\" : \"You are required to take all reasonable care to protect your self and your property and to act as though you are not insured.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Obligation\",\n" +
            "        \"party\" : \"You\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NysG16bOXQVJX4aN/ZCytvJsJh3ececBNzX/MPe4h+SY=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJY2iZEsIRez8saDb5Ft02vg=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ {\n" +
            "      \"label\" : \"Insurance\",\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"CKblIkZzZW15tJxkA7UO1eCXhm8b8fBqMBNtT/bXH8JI=\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 16981,\n" +
            "      \"end\" : 17185\n" +
            "    },\n" +
            "    \"sentence_text\" : \"You must read the insurance Policy carefully.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Obligation\",\n" +
            "        \"party\" : \"You\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NP2P8gvAc69bSB+ho9JenmciHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJY2iZEsIRez8saDb5Ft02vg=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ {\n" +
            "      \"label\" : \"Insurance\",\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"CqMWQ95EED6dB+IgJ9UvQIX3tQmLOyjCAmdhbkHdu3KNUjaYuk7NqGVMBC1x/54JJ\"\n" +
            "      }, {\n" +
            "        \"id\" : \"CpKcPktJKq3M0C9rNjil5FDGkdAfEaUyfIfVCHF4t4FQ=\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 21814,\n" +
            "      \"end\" : 21915\n" +
            "    },\n" +
            "    \"sentence_text\" : \"These are settled on an indemnity basis - not on a 'new for old' or replacement cost basis.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ {\n" +
            "      \"label\" : \"Indemnification\",\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"CnfTod6/kZ1D7c0mhysQ7LM2b864drYcUJ6sM2L5bK8E=\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 23422,\n" +
            "      \"end\" : 24696\n" +
            "    },\n" +
            "    \"sentence_text\" : \"GOVERNING LAW If you live in England, Scotland, Wales or Northern Ireland, the law applicable to where you live governs your Policy.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ {\n" +
            "      \"label\" : \"Dispute Resolution\",\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"CVB29XTgBHIFAaUoDkAPXboF5di1n1jxNUNP/5FfjVks=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"CM2a20KrJCoZPywpDkgFbR3E5LBKQtc+f+JEjWG+G4KM=\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Location\",\n" +
            "      \"text\" : \"England\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 23939,\n" +
            "        \"end\" : 23946\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Location\",\n" +
            "      \"text\" : \"Scotland\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 23948,\n" +
            "        \"end\" : 23956\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Location\",\n" +
            "      \"text\" : \"Wales\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 23958,\n" +
            "        \"end\" : 23963\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Location\",\n" +
            "      \"text\" : \"Northern Ireland\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 23967,\n" +
            "        \"end\" : 23983\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 37359,\n" +
            "      \"end\" : 37855\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Important Note - Changes in Your Health, or anyone on whose health your trip may depend, after you have purchased your Travel Insurance but prior to your trip commencing\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 78341,\n" +
            "      \"end\" : 79977\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Welcome to ACME Travel Insurance underwritten by ETI - International Travel Protection the United Kingdom branch of Europäische Reiseversicherung A.G., ( ERV ) an Ergo Group Company incorporated and regulated under the laws of Germany, ( ETI ) Companies House Registration FC 25660 and Branch Registration BR 007939.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ {\n" +
            "      \"label\" : \"Insurance\",\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"CqMWQ95EED6dB+IgJ9UvQIX3tQmLOyjCAmdhbkHdu3KNUjaYuk7NqGVMBC1x/54JJ\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Location\",\n" +
            "      \"text\" : \"United Kingdom\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 79091,\n" +
            "        \"end\" : 79105\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Location\",\n" +
            "      \"text\" : \"Germany\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 79560,\n" +
            "        \"end\" : 79567\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 82211,\n" +
            "      \"end\" : 82386\n" +
            "    },\n" +
            "    \"sentence_text\" : \"This Policy is available to all UK and Channel Island residents with an upper age limit of 74 for single trip insurance and an age limit of 69 for annual multi trip insurance.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ {\n" +
            "      \"label\" : \"Insurance\",\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"CqMWQ95EED6dB+IgJ9UvQIX3tQmLOyjCAmdhbkHdu3KNUjaYuk7NqGVMBC1x/54JJ\"\n" +
            "      }, {\n" +
            "        \"id\" : \"CpKcPktJKq3M0C9rNjil5FDGkdAfEaUyfIfVCHF4t4FQ=\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Location\",\n" +
            "      \"text\" : \"Channel Island\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 82250,\n" +
            "        \"end\" : 82264\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 82387,\n" +
            "      \"end\" : 82478\n" +
            "    },\n" +
            "    \"sentence_text\" : \"The Policy is only valid if purchased prior to the start of a trip from the United Kingdom.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ {\n" +
            "      \"label\" : \"Insurance\",\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"CpKcPktJKq3M0C9rNjil5FDGkdAfEaUyfIfVCHF4t4FQ=\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Location\",\n" +
            "      \"text\" : \"United Kingdom\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 82463,\n" +
            "        \"end\" : 82477\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 84137,\n" +
            "      \"end\" : 85258\n" +
            "    },\n" +
            "    \"sentence_text\" : \"The Policy Schedule shows important details including your premium amount and details of Insured Persons who are covered by this Policy.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 85961,\n" +
            "      \"end\" : 86730\n" +
            "    },\n" +
            "    \"sentence_text\" : \"The law, which applies to the Insurance Policy, is the law of that part of the United Kingdom in which you live.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Definition\",\n" +
            "        \"party\" : \"None\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"N5ngBniiB7B7vVF89jQI6Hg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PZhORqxxBCCtSUECV/h0aIA==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ {\n" +
            "      \"label\" : \"Dispute Resolution\",\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"CnvptvRdEmJBo0kv0HEfMxeywTEHXUuOhBT/fWIQJBxrdP5sOudqFSy28VRSoTHk9\"\n" +
            "      }, {\n" +
            "        \"id\" : \"CnvptvRdEmJBo0kv0HEfMxXagLG+WTZFywkYFmEt1Ott1lx2wHrS5scybqSLrrdD/\"\n" +
            "      }, {\n" +
            "        \"id\" : \"C4pcEkBqjwiTuF1mImdXJNqAzI6Sbt67OxOwQ1UtjfGctbdADZrWuMzZ8DciD3hNsrZiaA+PhlbTfU6PH1P9DPg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"C4pcEkBqjwiTuF1mImdXJNuwBPiNsDZllvZZtKCiQ4D0R9K16Mq7OZZhA1e06WWcJQF5YV8X33w5hCI9hWIPbIg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"CD8o45sy3B+3bfOura9ycHBbDDeDKPaOwaUiOVyUSt6OooA1nB8sL79Vb1T0FT4wK\"\n" +
            "      }, {\n" +
            "        \"id\" : \"CD8o45sy3B+3bfOura9ycHDp5EK++DtywVyrzyTwchIte8vJQK1XtcjQexmOnajM4\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Location\",\n" +
            "      \"text\" : \"United Kingdom\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 86210,\n" +
            "        \"end\" : 86224\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 86731,\n" +
            "      \"end\" : 87464\n" +
            "    },\n" +
            "    \"sentence_text\" : \"If you do not normally live in the United Kingdom, then English law applies.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ {\n" +
            "      \"label\" : \"Dispute Resolution\",\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"CnvptvRdEmJBo0kv0HEfMxeywTEHXUuOhBT/fWIQJBxrdP5sOudqFSy28VRSoTHk9\"\n" +
            "      }, {\n" +
            "        \"id\" : \"CnvptvRdEmJBo0kv0HEfMxXagLG+WTZFywkYFmEt1Ott1lx2wHrS5scybqSLrrdD/\"\n" +
            "      }, {\n" +
            "        \"id\" : \"C4pcEkBqjwiTuF1mImdXJNqAzI6Sbt67OxOwQ1UtjfGctbdADZrWuMzZ8DciD3hNsrZiaA+PhlbTfU6PH1P9DPg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"C4pcEkBqjwiTuF1mImdXJNuwBPiNsDZllvZZtKCiQ4D0R9K16Mq7OZZhA1e06WWcJQF5YV8X33w5hCI9hWIPbIg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Location\",\n" +
            "      \"text\" : \"United Kingdom\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 87264,\n" +
            "        \"end\" : 87278\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 87943,\n" +
            "      \"end\" : 89549\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Any information provided to us regarding you and/or Insured Persons will be processed by us, in compliance with the provisions of the Data Protection Act 1998, for the purpose of providing insurance and handling claims, if any, which may necessitate providing such information to third parties.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 92280,\n" +
            "      \"end\" : 94044\n" +
            "    },\n" +
            "    \"sentence_text\" : \"If you answer \\\"yes\\\" to questions 9 or 10 You must contact Our helpline to declare Your or Your travelling companion's health problem.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Obligation\",\n" +
            "        \"party\" : \"You\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NP2P8gvAc69bSB+ho9JenmciHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJY2iZEsIRez8saDb5Ft02vg=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ {\n" +
            "      \"label\" : \"Communication\",\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"CMtRFv6DN47u5R5VcFRPcicU6LUJIF6laRovnVh9kNv0=\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 94837,\n" +
            "      \"end\" : 96374\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Please note that if you fail to provide complete and accurate information in response to our questions or fail to inform us of any change in circumstances your policy may be invalidated and part or all of a claim may be not be paid.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ {\n" +
            "      \"label\" : \"Communication\",\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"CnEVP6oBkVoMdECqDDH25eg0doEN+wlEZljUy/U0lBxg=\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 98268,\n" +
            "      \"end\" : 99360\n" +
            "    },\n" +
            "    \"sentence_text\" : \"If you do not understand any part of your Policy or Schedule, please contact us on 0333 0030021 for assistance.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ {\n" +
            "      \"label\" : \"Communication\",\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"CMtRFv6DN47u5R5VcFRPcicU6LUJIF6laRovnVh9kNv0=\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 100967,\n" +
            "      \"end\" : 101037\n" +
            "    },\n" +
            "    \"sentence_text\" : \"POLICY RENEWAL - applicable for Annual Multi Trip Travel Policies only\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 101226,\n" +
            "      \"end\" : 101860\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Just Travel Insurance will send you a Renewal Notification Form approximately one month prior to the expiry of the current Policy Period.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 108689,\n" +
            "      \"end\" : 110184\n" +
            "    },\n" +
            "    \"sentence_text\" : \"If you choose to cancel and a claim has been made under this Policy during the Policy Period or an Insured Journey has been commenced, you will not be entitled to any premium refund.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Exclusion\",\n" +
            "        \"party\" : \"You\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"Nz+mQU2WrNC8+iI4CPZB3OlJBeznX2TKaZw9/+XELWyA=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJY2iZEsIRez8saDb5Ft02vg=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    }, {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Obligation\",\n" +
            "        \"party\" : \"You\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NB3SwB5WYDMm+Qn6T/buJd7RooP6K/4MhUH1TIJKhBiBAXlhXxfffDmEIj2FYg9si\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJY2iZEsIRez8saDb5Ft02vg=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 112907,\n" +
            "      \"end\" : 114320\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Cover for cancellation commences on the Cover Start Date shown on your Policy Schedule, or from the date an Insured Journey is booked (whichever is later) provided the booking is within the Policy Period, and terminates on commencement of the Insured Journey.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ {\n" +
            "      \"label\" : \"Term & Termination\",\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"CcdqLLi+1ALK6eldrNL17zyrPfGnRSFzFLssw6p74YIE=\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 114480,\n" +
            "      \"end\" : 116887\n" +
            "    },\n" +
            "    \"sentence_text\" : \"In respect of all other insurance in the Policy, cover commences from the effective date when you leave your usual place of residence to commence an Insured Journey, and continues until the time of your return to your usual place of residence on completion of the Insured Journey.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 119052,\n" +
            "      \"end\" : 119669\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Family members are only Insured under this Policy if they are named on the Policy Schedule and the appropriate premium has been paid.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ {\n" +
            "      \"label\" : \"Insurance\",\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"CKblIkZzZW15tJxkA7UO1eCXhm8b8fBqMBNtT/bXH8JI=\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 120872,\n" +
            "      \"end\" : 121310\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Any one Leisure Trip covered by an Annual Multi Trip Travel Insurance Policy is limited to a maximum of 31 days.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 121778,\n" +
            "      \"end\" : 121986\n" +
            "    },\n" +
            "    \"sentence_text\"\n" +
            "    : \"They are shown on your Policy Schedule.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 122146,\n" +
            "      \"end\" : 122984\n" +
            "    },\n" +
            "    \"sentence_text\" : \"No cover shall be provided for any part of any trip under an annual multi trip policy where your intended travel exceeds the maximum permitted travel days granted under your policy.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 130448,\n" +
            "      \"end\" : 132983\n" +
            "    },\n" +
            "    \"sentence_text\" : \"We will be entitled to take over and conduct in your name (at our expense) the defence or settlement of any claim or to prosecute in your name to our own benefit in respect of any claim for indemnity or damage or otherwise, and will have full discretion in the conduct of any proceedings or in settlement of any claim and you will give all such information and reasonable assistance as we require.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Obligation\",\n" +
            "        \"party\" : \"We\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NDSXgDE2EK5XC/C8uYTOg8UBeWFfF998OYQiPYViD2yI=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 132984,\n" +
            "      \"end\" : 133142\n" +
            "    },\n" +
            "    \"sentence_text\" : \"This will include legal action to get compensation from anyone else and/or legal action to get back from anyone else any payments that have already been made.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ {\n" +
            "      \"label\" : \"Payment Terms & Billing\",\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"CnLh2GKlYzgEXhAdVezLewQMEwwNq/h4G3CJ2ZDT8vHk=\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 133313,\n" +
            "      \"end\" : 134597\n" +
            "    },\n" +
            "    \"sentence_text\" : \"You may not settle, reject or negotiate any claim without written permission to do so from us (or DAS in respect of Policy Section 11 ).\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Exclusion\",\n" +
            "        \"party\" : \"You\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"No7d6UHpTr1DBkLsVFmUncewHzqqrPHlIpdohMwLkxXY=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJY2iZEsIRez8saDb5Ft02vg=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 134787,\n" +
            "      \"end\" : 138130\n" +
            "    },\n" +
            "    \"sentence_text\" : \"In case of Illness or Bodily Injury we may approach any doctor who may have treated you during the period of three years prior to the claim and we may at our own expense, and upon reasonable notice to you or your legal personal representative, arrange for you to be medically examined as often as required, or in the event of death, have a post mortem examination of your body.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 138300,\n" +
            "      \"end\" : 139268\n" +
            "    },\n" +
            "    \"sentence_text\" : \"You will supply, at your own expense, a doctor's certificate in the form required by us in support of any medical-related claim under the Policy.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Obligation\",\n" +
            "        \"party\" : \"You\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NNtzdPi0PFR+R0Gs6JgoYuk04j4A/cYGPkRk6uzIdLjU=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJY2iZEsIRez8saDb5Ft02vg=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 1450,\n" +
            "      \"end\" : 1473\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Travel Insurance Policy\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 1683,\n" +
            "      \"end\" : 1702\n" +
            "    },\n" +
            "    \"sentence_text\" : \"ACME Insurance Inc.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 1895,\n" +
            "      \"end\" : 1903\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Contents\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 2424,\n" +
            "      \"end\" : 2439\n" +
            "    },\n" +
            "    \"sentence_text\" : \"IMPORTANT NOTES\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 2891,\n" +
            "      \"end\" : 2908\n" +
            "    },\n" +
            "    \"sentence_text\" : \"TABLE OF BENEFITS\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 3363,\n" +
            "      \"end\" : 3395\n" +
            "    },\n" +
            "    \"sentence_text\" : \"WELCOME TO ACME TRAVEL INSURANCE\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 3849,\n" +
            "      \"end\" : 3867\n" +
            "    },\n" +
            "    \"sentence_text\" : \"POLICY INFORMATION\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 4320,\n" +
            "      \"end\" : 4337\n" +
            "    },\n" +
            "    \"sentence_text\" : \"POLICY CONDITIONS\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 4791,\n" +
            "      \"end\" : 4809\n" +
            "    },\n" +
            "    \"sentence_text\" : \"GENERAL EXCLUSIONS\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 5288,\n" +
            "      \"end\" : 5318\n" +
            "    },\n" +
            "    \"sentence_text\" : \"RIGHTS AND RESPONSIBILITIES 10\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 5564,\n" +
            "      \"end\" : 5579\n" +
            "    },\n" +
            "    \"sentence_text\" : \"IMPORTANT NOTES\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 6949,\n" +
            "      \"end\" : 6964\n" +
            "    },\n" +
            "    \"sentence_text\" : \"POLICY EXCESSES\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 7161,\n" +
            "      \"end\" : 7228\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Claims under most sections of the Policy will be subject to excess.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 7229,\n" +
            "      \"end\" : 7641\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Where there is excess you will be responsible for paying the first part of that claim.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Obligation\",\n" +
            "        \"party\" : \"You\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"N5ODIQa4bjoPGP8mCpIAP+dGUoYO3BP9MpXMGMlGR5RI=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJY2iZEsIRez8saDb5Ft02vg=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 7907,\n" +
            "      \"end\" : 7922\n" +
            "    },\n" +
            "    \"sentence_text\" : \"REASONABLE CARE\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 9665,\n" +
            "      \"end\" : 9675\n" +
            "    },\n" +
            "    \"sentence_text\" : \"COMPLAINTS\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 9873,\n" +
            "      \"end\" : 10982\n" +
            "    },\n" +
            "    \"sentence_text\" : \"The insurance Policy includes a Complaints Procedure, which tells you what steps you can take if you wish to make a complaint.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Right\",\n" +
            "        \"party\" : \"You\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NFcxTEbqRNqPlGopeAMxC8YOFjt/T/aNeYQ61ZTKoNOU=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJY2iZEsIRez8saDb5Ft02vg=\"\n" +
            "      }, {\n" +
            "\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 11248,\n" +
            "      \"end\" : 11266\n" +
            "    },\n" +
            "    \"sentence_text\" : \"COOLING OFF PERIOD\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 11464,\n" +
            "      \"end\" : 13063\n" +
            "    },\n" +
            "    \"sentence_text\" : \"The cover under Section 5a Cancellation, commences as soon as the policy is issued, we cannot therefore, refund your premium after this date except within the first 14 days of the policy being received or before you travel (whichever is sooner), if it does not meet your requirements.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Exclusion\",\n" +
            "        \"party\" : \"We\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"No7d6UHpTr1DBkLsVFmUncewHzqqrPHlIpdohMwLkxXY=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 13331,\n" +
            "      \"end\" : 13364\n" +
            "    },\n" +
            "    \"sentence_text\" : \"HAZARDOUS ACTIVITIES & SPORTS\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 13560,\n" +
            "      \"end\" : 15386\n" +
            "    },\n" +
            "    \"sentence_text\" : \"The Policy will not cover you when you take part in any hazardous activities unless you have declared this to us and paid an additional premium and it is stated in the policy schedule.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 15652,\n" +
            "      \"end\" : 15659\n" +
            "    },\n" +
            "    \"sentence_text\" : \"CRUISES\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 15855,\n" +
            "      \"end\" : 16239\n" +
            "    },\n" +
            "    \"sentence_text\" : \"The Policy will not cover you for trips on Cruise-ships.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 16505,\n" +
            "      \"end\" : 16521\n" +
            "    },\n" +
            "    \"sentence_text\" : \"INSURANCE POLICY\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 16718,\n" +
            "      \"end\" : 16813\n" +
            "    },\n" +
            "    \"sentence_text\" : \"This contains full details of the cover provided plus the conditions and exclusions that apply.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 17452,\n" +
            "      \"end\" : 17477\n" +
            "    },\n" +
            "    \"sentence_text\" : \"CONDITIONS AND EXCLUSIONS\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 17673,\n" +
            "      \"end\" : 17745\n" +
            "    },\n" +
            "    \"sentence_text\" : \"There are conditions and exclusions, which apply to individual sections.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 17746,\n" +
            "      \"end\" : 17813\n" +
            "    },\n" +
            "    \"sentence_text\" : \"General conditions, exclusions and terms apply to the whole Policy.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 18079,\n" +
            "      \"end\" : 18096\n" +
            "    },\n" +
            "    \"sentence_text\" : \"FRAUDULENT CLAIMS\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 18294,\n" +
            "      \"end\" : 18349\n" +
            "    },\n" +
            "    \"sentence_text\" : \"The making of a fraudulent claim is a criminal offence.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Definition\",\n" +
            "        \"party\" : \"None\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"Nh2s5Sr5m0yzFf0ex23a6H5dzKdvI+yC8XrQ3Q3Id3E0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PZhORqxxBCCtSUECV/h0aIA==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 18616,\n" +
            "      \"end\" : 18631\n" +
            "    },\n" +
            "    \"sentence_text\" : \"CYBER-TERRORISM\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 18826,\n" +
            "      \"end\" : 19391\n" +
            "    },\n" +
            "    \"sentence_text\" : \"The Policy will not cover you for the consequences of Cyber Terrorism.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 19657,\n" +
            "      \"end\" : 19673\n" +
            "    },\n" +
            "    \"sentence_text\" : \"MEDICAL EXPENSES\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 19870,\n" +
            "      \"end\" : 19975\n" +
            "    },\n" +
            "    \"sentence_text\" : \"This section does not provide private health care unless specifically approved by the Assistance Company.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 20241,\n" +
            "      \"end\" : 20247\n" +
            "    },\n" +
            "    \"sentence_text\" : \"HEALTH\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 20445,\n" +
            "      \"end\" : 20577\n" +
            "    },\n" +
            "    \"sentence_text\" : \"The Policy contains conditions relating to the health of the people travelling and others upon whose well-being the trip may depend.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 20578,\n" +
            "      \"end\" : 21334\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Please refer to page 1 & 2 of the Policy for Pre-Existing Medical Conditions and exclusions.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 21601,\n" +
            "      \"end\" : 21616\n" +
            "    },\n" +
            "    \"sentence_text\" : \"PROPERTY CLAIMS\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 22184,\n" +
            "      \"end\" : 22197\n" +
            "    },\n" +
            "    \"sentence_text\" : \"POLICY LIMITS\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 22395,\n" +
            "      \"end\" : 22809\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Most sections of the Policy have limits on the amount we will pay under that section.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 22810,\n" +
            "      \"end\" : 23223\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Some sections also include inner limits e.g.: for one item or for valuables in total.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 24697,\n" +
            "      \"end\" : 25455\n" +
            "    },\n" +
            "    \"sentence_text\" : \"If you live outside of England, Scotland, Wales or Northern Ireland, English law governs your Policy.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Location\",\n" +
            "      \"text\" : \"England\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 25050,\n" +
            "        \"end\" : 25057\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Location\",\n" +
            "      \"text\" : \"Scotland\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 25059,\n" +
            "        \"end\" : 25067\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Location\",\n" +
            "      \"text\" : \"Wales\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 25069,\n" +
            "        \"end\" : 25074\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Location\",\n" +
            "      \"text\" : \"Northern Ireland\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 25078,\n" +
            "        \"end\" : 25094\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 25723,\n" +
            "      \"end\" : 25754\n" +
            "    },\n" +
            "    \"sentence_text\" : \"PRE-EXISTING MEDICAL CONDITIONS\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 25959,\n" +
            "      \"end\" : 26532\n" +
            "    },\n" +
            "    \"sentence_text\" : \"You must comply with the following conditions to have full protection under your policy:\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Obligation\",\n" +
            "        \"party\" : \"You\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NP2P8gvAc69bSB+ho9JenmciHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJY2iZEsIRez8saDb5Ft02vg=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 26731,\n" +
            "      \"end\" : 28547\n" +
            "    },\n" +
            "    \"sentence_text\" : \"You are not covered (for the relevant condition) for claims directly or indirectly resulting from you, or anyone in your travelling party, or anyone on whose health your trip may depend, having suffered from, or been treated for, or diagnosed with, any medical condition unless declared to us and shown as 'covered' on the Medical Declaration.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 28737,\n" +
            "      \"end\" : 28777\n" +
            "    },\n" +
            "    \"sentence_text\" : \"A pre-existing medical condition must be\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 28967,\n" +
            "      \"end\" : 29014\n" +
            "    },\n" +
            "    \"sentence_text\" : \"declared if: Anyone travelling who has ever had\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 29202,\n" +
            "      \"end\" : 29216\n" +
            "    },\n" +
            "    \"sentence_text\" : \"treatment for:\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 29433,\n" +
            "      \"end\" : 29657\n" +
            "    },\n" +
            "    \"sentence_text\" : \"a. Any heart or circulatory condition b. Any type of diabetes\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 29852,\n" +
            "      \"end\" : 29886\n" +
            "    },\n" +
            "    \"sentence_text\" : \"c. A stroke or high blood pressure\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 30075,\n" +
            "      \"end\" : 30241\n" +
            "    },\n" +
            "    \"sentence_text\" : \"d. Any type of cancer, whether in remission or not e. Any lung or breathing condition f. Any psychiatric or psychological condition g. An organ transplant or dialysis\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 30430,\n" +
            "      \"end\" : 30623\n" +
            "    },\n" +
            "    \"sentence_text\" : \"In the last 5 years, anyone has travelling suffered from a serious or recurring medical condition, been prescribed medication or received treatment or attended a medical practitioner's surgery.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 30812,\n" +
            "      \"end\" : 30996\n" +
            "    },\n" +
            "    \"sentence_text\" : \"In the last 5 years, anyone travelling has been referred to a specialist or a consultant at a hospital or clinic for tests, diagnosis or treatments or attended as an in or out patient.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 31186,\n" +
            "      \"end\" : 31275\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Anyone has been diagnosed by a medical practitioner as suffering from a terminal illness.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 31444,\n" +
            "      \"end\" : 31467\n" +
            "    },\n" +
            "    \"sentence_text\" : \"You will not be covered\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Obligation\",\n" +
            "        \"party\" : \"You\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NB3SwB5WYDMm+Qn6T/buJd7RooP6K/4MhUH1TIJKhBiBAXlhXxfffDmEIj2FYg9si\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJY2iZEsIRez8saDb5Ft02vg=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 31689,\n" +
            "      \"end\" : 33430\n" +
            "    },\n" +
            "    \"sentence_text\" : \"a. for any claim arising from a medical condition of someone you were going to stay with, a relative, a business associate, a travelling companion, or anyone on whose health your trip may depend if you are aware of the medical condition at the time your policy was issued.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Obligation\",\n" +
            "        \"party\" : \"You\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NB3SwB5WYDMm+Qn6T/buJd7RooP6K/4MhUH1TIJKhBiCl5QEQ7kEENnGTqVBIMR8LN4hxHA6uQbcJumJiz0xnEw==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJaWchv5IayLs3uQRnhHFGG8DvZ+3PKg5AgwXm4bPWQhXFHWCk25tlvugaq2zdjwg1Q==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3Yg3J53G1OKrVtdwiRa2A66YwznuIZKl37/4YxKfAOocD\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e4DBD1HjkE8/fhd4g2slwWlW4Hf+L0fXmrlNT68XGiuFQF5YV8X33w5hCI9hWIPbIg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"Pqjd5I+s/Fdpx2NbIwCRMtztzc5JdzB+aHfojSiMbztZp4u1DZxlwdAHWePYv+wfs\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 33618,\n" +
            "      \"end\" : 34911\n" +
            "    },\n" +
            "    \"sentence_text\" : \"b. if you have a medical condition, if you are travelling against medical advice or medical advice should have been sought before commencing your journey.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Obligation\",\n" +
            "        \"party\" : \"You\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NB3SwB5WYDMm+Qn6T/buJd7RooP6K/4MhUH1TIJKhBiCl5QEQ7kEENnGTqVBIMR8LN4hxHA6uQbcJumJiz0xnEw==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJaWchv5IayLs3uQRnhHFGG8DvZ+3PKg5AgwXm4bPWQhXFHWCk25tlvugaq2zdjwg1Q==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3Yg3J53G1OKrVtdwiRa2A66YwznuIZKl37/4YxKfAOocD\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e4DBD1HjkE8/fhd4g2slwWlW4Hf+L0fXmrlNT68XGiuFQF5YV8X33w5hCI9hWIPbIg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"Pqjd5I+s/Fdpx2NbIwCRMtztzc5JdzB+aHfojSiMbztZp4u1DZxlwdAHWePYv+wfs\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 34912,\n" +
            "      \"end\" : 36347\n" +
            "    },\n" +
            "    \"sentence_text\" : \"c. if you know you will need medical treatment during your journey or you are travelling specifically to get medical treatment.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Obligation\",\n" +
            "        \"party\" : \"You\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NNtzdPi0PFR+R0Gs6JgoYuk04j4A/cYGPkRk6uzIdLjU=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJY2iZEsIRez8saDb5Ft02vg=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"NB3SwB5WYDMm+Qn6T/buJd7RooP6K/4MhUH1TIJKhBiCl5QEQ7kEENnGTqVBIMR8LN4hxHA6uQbcJumJiz0xnEw==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJaWchv5IayLs3uQRnhHFGG8DvZ+3PKg5AgwXm4bPWQhXFHWCk25tlvugaq2zdjwg1Q==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3Yg3J53G1OKrVtdwiRa2A66YwznuIZKl37/4YxKfAOocD\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e4DBD1HjkE8/fhd4g2slwWlW4Hf+L0fXmrlNT68XGiuFQF5YV8X33w5hCI9hWIPbIg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"Pqjd5I+s/Fdpx2NbIwCRMtztzc5JdzB+aHfojSiMbztZp4u1DZxlwdAHWePYv+wfs\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 36535,\n" +
            "      \"end\" : 37154\n" +
            "    },\n" +
            "    \"sentence_text\" : \"d. if you have a medical condition for which treatment is awaited as a hospital in-patient or for which diagnostic tests are pending.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Obligation\",\n" +
            "        \"party\" : \"You\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NB3SwB5WYDMm+Qn6T/buJd7RooP6K/4MhUH1TIJKhBiCl5QEQ7kEENnGTqVBIMR8LN4hxHA6uQbcJumJiz0xnEw==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJaWchv5IayLs3uQRnhHFGG8DvZ+3PKg5AgwXm4bPWQhXFHWCk25tlvugaq2zdjwg1Q==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3Yg3J53G1OKrVtdwiRa2A66YwznuIZKl37/4YxKfAOocD\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e4DBD1HjkE8/fhd4g2slwWlW4Hf+L0fXmrlNT68XGiuFQF5YV8X33w5hCI9hWIPbIg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"Pqjd5I+s/Fdpx2NbIwCRMtztzc5JdzB+aHfojSiMbztZp4u1DZxlwdAHWePYv+wfs\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 38053,\n" +
            "      \"end\"\n" +
            "      : 40064\n" +
            "    },\n" +
            "    \"sentence_text\" : \"You must tell us if your state of health, or that of anyone on whose health your trip may depend, changes before you commence an insured trip, i.e. if you or they develop a new condition or an existing condition worsens.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Obligation\",\n" +
            "        \"party\" : \"You\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NP2P8gvAc69bSB+ho9JenmciHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJY2iZEsIRez8saDb5Ft02vg=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 40065,\n" +
            "      \"end\" : 41846\n" +
            "    },\n" +
            "    \"sentence_text\" : \"If you DO NOT tell us about a change in your or their medical condition we have the right to amend, restrict or cancel your cover under this policy.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Right\",\n" +
            "        \"party\" : \"We\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"Nzn2xAL9DPjp1u03FxIJHfhnMLvMi4sizhxdSHoirALo=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 42047,\n" +
            "      \"end\" : 42092\n" +
            "    },\n" +
            "    \"sentence_text\" : \"If you have a pre-existing medical condition?\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 42280,\n" +
            "      \"end\" : 43447\n" +
            "    },\n" +
            "    \"sentence_text\" : \"If you want to purchase or have purchased a policy and need to declare a pre-existing medical condition, (as advised above), then we will need to discuss this with you in person.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Obligation\",\n" +
            "        \"party\" : \"We\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NP2P8gvAc69bSB+ho9JenmciHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"NNtzdPi0PFR+R0Gs6JgoYuk04j4A/cYGPkRk6uzIdLjU=\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 43448,\n" +
            "      \"end\" : 44368\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Please telephone us on 0333 0030021 for immediate help - We look forward to talking with you.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 44559,\n" +
            "      \"end\" : 44976\n" +
            "    },\n" +
            "    \"sentence_text\" : \"For some pre-existing medical conditions you may be required to pay an additional premium.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Obligation\",\n" +
            "        \"party\" : \"You\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NysG16bOXQVJX4aN/ZCytvJsJh3ececBNzX/MPe4h+SY=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJY2iZEsIRez8saDb5Ft02vg=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 45167,\n" +
            "      \"end\" : 45629\n" +
            "    },\n" +
            "    \"sentence_text\" : \"In all cases, cover for pre-existing medical conditions is dealt with on a one-to-one personal level through our telesales call centre.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 45813,\n" +
            "      \"end\" : 45830\n" +
            "    },\n" +
            "    \"sentence_text\" : \"TABLE OF BENEFITS\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 46421,\n" +
            "      \"end\" : 46426\n" +
            "    },\n" +
            "    \"sentence_text\" : \"COVER\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 46662,\n" +
            "      \"end\" : 46677\n" +
            "    },\n" +
            "    \"sentence_text\" : \"BRONZE Benefits\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 46912,\n" +
            "      \"end\" : 46927\n" +
            "    },\n" +
            "    \"sentence_text\" : \"SILVER Benefits\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 47162,\n" +
            "      \"end\" : 47175\n" +
            "    },\n" +
            "    \"sentence_text\" : \"GOLD Benefits\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 47551,\n" +
            "      \"end\" : 47558\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Max Sum\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 47791,\n" +
            "      \"end\" : 47796\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Exces\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 48031,\n" +
            "      \"end\" : 48046\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Max Sum Insured\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 48282,\n" +
            "      \"end\" : 48287\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Exces\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 48523,\n" +
            "      \"end\" : 48538\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Max Sum Insured\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 48768,\n" +
            "      \"end\" : 48773\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Exces\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 49244,\n" +
            "      \"end\" : 49343\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Emergency Medical, Repatriation and associated Expenses Emergency Dental Treatment Hospital Benefit\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 49579,\n" +
            "      \"end\" : 49586\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Insured\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 49753,\n" +
            "      \"end\" : 49768\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£5,000,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£5,000,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 49753,\n" +
            "        \"end\" : 49768\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 49936,\n" +
            "      \"end\" : 49953\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£100 £0\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£100\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 49936,\n" +
            "        \"end\" : 49945\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"100 £\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 49942,\n" +
            "        \"end\" : 49947\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£0\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 49946,\n" +
            "        \"end\" : 49953\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 50354,\n" +
            "      \"end\" : 50363\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£100\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£100\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 50354,\n" +
            "        \"end\" : 50363\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 50530,\n" +
            "      \"end\" : 50537\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 50764,\n" +
            "      \"end\" : 50780\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£10,000,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£10,000,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 50764,\n" +
            "        \"end\" : 50780\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 50948,\n" +
            "      \"end\" : 50957\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£200\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£200\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 50948,\n" +
            "        \"end\" : 50957\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 51124,\n" +
            "      \"end\" : 51157\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£20 per day up to £1000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£20\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 51124,\n" +
            "        \"end\" : 51132\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£1000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 51147,\n" +
            "        \"end\" : 51157\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 51558,\n" +
            "      \"end\" : 51566\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£75\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£75\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 51558,\n" +
            "        \"end\" : 51566\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 51733,\n" +
            "      \"end\" : 51740\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 51966,\n" +
            "      \"end\" : 51982\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£10,000,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£10,000,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 51966,\n" +
            "        \"end\" : 51982\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 52149,\n" +
            "      \"end\" : 52158\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£300\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£300\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 52149,\n" +
            "        \"end\" : 52158\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 52326,\n" +
            "      \"end\" : 52359\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£20 per day up to £1000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£20\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 52326,\n" +
            "        \"end\" : 52334\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£1000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 52349,\n" +
            "        \"end\" : 52359\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 52756,\n" +
            "      \"end\" : 52764\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£50\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£50\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 52756,\n" +
            "        \"end\" : 52764\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 52929,\n" +
            "      \"end\" : 52936\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 53406,\n" +
            "      \"end\" : 53423\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Personal Accident\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, \n";

    private static String BACKUP_DATA_P2 = "{\n" +

            "    \"sentence\" : {\n" +
            "      \"begin\" : 53649,\n" +
            "      \"end\" : 53656\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£0\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£0\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 53649,\n" +
            "        \"end\" : 53656\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 53881,\n" +
            "      \"end\" : 53884\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 54111,\n" +
            "      \"end\" : 54123\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£15,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£15,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 54111,\n" +
            "        \"end\" : 54123\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 54348,\n" +
            "      \"end\" : 54351\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 54577,\n" +
            "      \"end\" : 54589\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£25,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£25,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 54577,\n" +
            "        \"end\" : 54589\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 54812,\n" +
            "      \"end\" : 54815\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 55286,\n" +
            "      \"end\" : 55329\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Medical Disablement/Infection Aged 18 to 64\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 55505,\n" +
            "      \"end\" : 55529\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Aged under 18 or over 64\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 55756,\n" +
            "      \"end\" : 55771\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£0 £0\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£0\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 55756,\n" +
            "        \"end\" : 55763\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"0 £\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 55762,\n" +
            "        \"end\" : 55765\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£0\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 55764,\n" +
            "        \"end\" : 55771\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 55997,\n" +
            "      \"end\" : 56004\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 56232,\n" +
            "      \"end\" : 56256\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£10,000 £2,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£10,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 56232,\n" +
            "        \"end\" : 56244\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"10,000 £\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 56238,\n" +
            "        \"end\" : 56246\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£2,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 56245,\n" +
            "        \"end\" : 56256\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 56482,\n" +
            "      \"end\" : 56489\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 56716,\n" +
            "      \"end\" : 56740\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£15,000 £2,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£15,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 56716,\n" +
            "        \"end\" : 56728\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"15,000 £\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 56722,\n" +
            "        \"end\" : 56730\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£2,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 56729,\n" +
            "        \"end\" : 56740\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 56964,\n" +
            "      \"end\" : 56971\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 57442,\n" +
            "      \"end\" : 57469\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Provision of Screened Blood\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 57695,\n" +
            "      \"end\" : 57707\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£25,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£25,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 57695,\n" +
            "        \"end\" : 57707\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 57932,\n" +
            "      \"end\" : 57935\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 58162,\n" +
            "      \"end\" : 58174\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£25,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£25,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 58162,\n" +
            "        \"end\" : 58174\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 58399,\n" +
            "      \"end\" : 58402\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 58628,\n" +
            "      \"end\" : 58640\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£25,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£25,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 58628,\n" +
            "        \"end\" : 58640\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 58863,\n" +
            "      \"end\" : 58866\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 59101,\n" +
            "      \"end\" : 59103\n" +
            "    },\n" +
            "    \"sentence_text\" : \"5a\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 59339,\n" +
            "      \"end\" : 59351\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Cancellation\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 59577,\n" +
            "      \"end\" : 59588\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£2,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£2,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 59577,\n" +
            "        \"end\" : 59588\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 59813,\n" +
            "      \"end\" : 59822\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£100\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£100\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 59813,\n" +
            "        \"end\" : 59822\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 60049,\n" +
            "      \"end\" : 60060\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£5,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£5,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 60049,\n" +
            "        \"end\" : 60060\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 60285,\n" +
            "      \"end\" : 60293\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£75\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£75\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 60285,\n" +
            "        \"end\" : 60293\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 60519,\n" +
            "      \"end\" : 60531\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£10,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£10,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 60519,\n" +
            "        \"end\" : 60531\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 60754,\n" +
            "      \"end\" : 60762\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£50\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£50\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 60754,\n" +
            "        \"end\" : 60762\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 60996,\n" +
            "      \"end\" : 60998\n" +
            "    },\n" +
            "    \"sentence_text\" : \"5b\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 61233,\n" +
            "      \"end\" : 61244\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Curtailment\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 61469,\n" +
            "      \"end\" : 61480\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£2,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£2,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 61469,\n" +
            "        \"end\" : 61480\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 61704,\n" +
            "      \"end\" : 61713\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£100\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£100\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 61704,\n" +
            "        \"end\" : 61713\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 61939,\n" +
            "      \"end\" : 61950\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£5,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£5,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 61939,\n" +
            "        \"end\" : 61950\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 62174,\n" +
            "      \"end\" : 62182\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£75\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£75\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 62174,\n" +
            "        \"end\" : 62182\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 62407,\n" +
            "      \"end\" : 62419\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£10,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£10,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 62407,\n" +
            "        \"end\" : 62419\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 62641,\n" +
            "      \"end\" : 62649\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£50\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£50\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 62641,\n" +
            "        \"end\" : 62649\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 63118,\n" +
            "      \"end\" : 63149\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Travel Delay on Outward Journey\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 63374,\n" +
            "      \"end\" : 63381\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£0\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£0\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 63374,\n" +
            "        \"end\" : 63381\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 63605,\n" +
            "      \"end\" : 63608\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 63834,\n" +
            "      \"end\" : 63870\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£20 per 8 hours up to £250\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£20\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 63834,\n" +
            "        \"end\" : 63842\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£250\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 63861,\n" +
            "        \"end\" : 63870\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 64094,\n" +
            "      \"end\" : 64097\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 64323,\n" +
            "      \"end\" : 64359\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£20 per 8 hours up to £400\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£20\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 64323,\n" +
            "        \"end\" : 64331\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£400\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 64350,\n" +
            "        \"end\" : 64359\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 64582,\n" +
            "      \"end\" : 64585\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 64821,\n" +
            "      \"end\" : 64823\n" +
            "    },\n" +
            "    \"sentence_text\" : \"7a\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 65059,\n" +
            "      \"end\" : 65087\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Personal Effects and Baggage\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 65314,\n" +
            "      \"end\" : 65323\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£750\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£750\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 65314,\n" +
            "        \"end\" : 65323\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 65549,\n" +
            "      \"end\" : 65558\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£100\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£100\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 65549,\n" +
            "        \"end\" : 65558\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 65786,\n" +
            "      \"end\" : 65797\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£1,500\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£1,500\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 65786,\n" +
            "        \"end\" : 65797\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\"\n" +
            "    : {\n" +
            "      \"begin\" : 66023,\n" +
            "      \"end\" : 66031\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£75\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£75\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 66023,\n" +
            "        \"end\" : 66031\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 66258,\n" +
            "      \"end\" : 66269\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£1,500\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£1,500\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 66258,\n" +
            "        \"end\" : 66269\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 66493,\n" +
            "      \"end\" : 66501\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£50\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£50\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 66493,\n" +
            "        \"end\" : 66501\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 66737,\n" +
            "      \"end\" : 66739\n" +
            "    },\n" +
            "    \"sentence_text\" : \"7b\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 66975,\n" +
            "      \"end\" : 66998\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Loss of Travel Document\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 67225,\n" +
            "      \"end\" : 67232\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£0\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£0\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 67225,\n" +
            "        \"end\" : 67232\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 67458,\n" +
            "      \"end\" : 67461\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 67689,\n" +
            "      \"end\" : 67698\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£200\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£200\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 67689,\n" +
            "        \"end\" : 67698\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 67924,\n" +
            "      \"end\" : 67927\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 68154,\n" +
            "      \"end\" : 68163\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£350\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£350\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 68154,\n" +
            "        \"end\" : 68163\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 68387,\n" +
            "      \"end\" : 68390\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 68859,\n" +
            "      \"end\" : 68877\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Personal Liability\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 69103,\n" +
            "      \"end\" : 69110\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£0\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£0\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 69103,\n" +
            "        \"end\" : 69110\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 69335,\n" +
            "      \"end\" : 69338\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 69566,\n" +
            "      \"end\" : 69604\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£1,000,000 £100,000 property\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£1,000,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 69566,\n" +
            "        \"end\" : 69581\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"1,000,000 £\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 69572,\n" +
            "        \"end\" : 69583\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£100,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 69582,\n" +
            "        \"end\" : 69595\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 69829,\n" +
            "      \"end\" : 69838\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£250\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£250\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 69829,\n" +
            "        \"end\" : 69838\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 70003,\n" +
            "      \"end\" : 70012\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£250\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£250\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 70003,\n" +
            "        \"end\" : 70012\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 70239,\n" +
            "      \"end\" : 70277\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£2,000,000 £100,000 property\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£2,000,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 70239,\n" +
            "        \"end\" : 70254\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"2,000,000 £\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 70245,\n" +
            "        \"end\" : 70256\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£100,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 70255,\n" +
            "        \"end\" : 70268\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 70500,\n" +
            "      \"end\" : 70509\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£250\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£250\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 70500,\n" +
            "        \"end\" : 70509\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 70673,\n" +
            "      \"end\" : 70682\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£250\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£250\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 70673,\n" +
            "        \"end\" : 70682\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 71156,\n" +
            "      \"end\" : 71225\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Hijack, Kidnap and Mugging Hospitalisation following a Mugging Attack\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 71452,\n" +
            "      \"end\" : 71459\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£0\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£0\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 71452,\n" +
            "        \"end\" : 71459\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 71626,\n" +
            "      \"end\" : 71633\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£0\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£0\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 71626,\n" +
            "        \"end\" : 71633\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 71859,\n" +
            "      \"end\" : 71862\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 72028,\n" +
            "      \"end\" : 72031\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 72258,\n" +
            "      \"end\" : 72269\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Damage Only\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 72437,\n" +
            "      \"end\" : 72446\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£500\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£500\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 72437,\n" +
            "        \"end\" : 72446\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 72613,\n" +
            "      \"end\" : 72645\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£20 per day up to £100\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£20\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 72613,\n" +
            "        \"end\" : 72621\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£100\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 72636,\n" +
            "        \"end\" : 72645\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 72871,\n" +
            "      \"end\" : 72874\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "\n" +
            "      \"begin\" : 73040,\n" +
            "      \"end\" : 73043\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 73270,\n" +
            "      \"end\" : 73281\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Damage Only\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 73448,\n" +
            "      \"end\" : 73459\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£1,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£1,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 73448,\n" +
            "        \"end\" : 73459\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 73627,\n" +
            "      \"end\" : 73659\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£20 per day up to £500\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£20\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 73627,\n" +
            "        \"end\" : 73635\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£500\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 73650,\n" +
            "        \"end\" : 73659\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 73883,\n" +
            "      \"end\" : 73886\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 74050,\n" +
            "      \"end\" : 74053\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 74289,\n" +
            "      \"end\" : 74291\n" +
            "    },\n" +
            "    \"sentence_text\" : \"10\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 74528,\n" +
            "      \"end\" : 74539\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Catastrophe\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 74766,\n" +
            "      \"end\" : 74773\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£0\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£0\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 74766,\n" +
            "        \"end\" : 74773\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 74999,\n" +
            "      \"end\" : 75002\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 75230,\n" +
            "      \"end\" : 75239\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£500\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£500\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 75230,\n" +
            "        \"end\" : 75239\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 75465,\n" +
            "      \"end\" : 75468\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 75695,\n" +
            "      \"end\" : 75706\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£1,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£1,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 75695,\n" +
            "        \"end\" : 75706\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 75930,\n" +
            "      \"end\" : 75933\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 76169,\n" +
            "      \"end\" : 76171\n" +
            "    },\n" +
            "    \"sentence_text\" : \"11\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 76407,\n" +
            "      \"end\" : 76433\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Legal Costs & Expenses\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 76660,\n" +
            "      \"end\" : 76672\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£25,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£25,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 76660,\n" +
            "        \"end\" : 76672\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 76898,\n" +
            "      \"end\" : 76901\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 77129,\n" +
            "      \"end\" : 77141\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£25,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£25,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 77129,\n" +
            "        \"end\" : 77141\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 77367,\n" +
            "      \"end\" : 77370\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 77598,\n" +
            "      \"end\" : 77610\n" +
            "    },\n" +
            "    \"sentence_text\" : \"£25,000\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Currency\",\n" +
            "      \"text\" : \"£25,000\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 77598,\n" +
            "        \"end\" : 77610\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 77835,\n" +
            "      \"end\" : 77838\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Nil\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 78113,\n" +
            "      \"end\" : 78145\n" +
            "    },\n" +
            "    \"sentence_text\" : \"WELCOME TO ACME TRAVEL INSURANCE\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 80149,\n" +
            "      \"end\" : 80894\n" +
            "    },\n" +
            "    \"sentence_text\" : \"ERV is authorised by the Bundesanstalt für Finanzdienstleistungsaufsicht (BAFIN - www.bafin.de) and the Prudential Regulation Authority (PRA), and subject to limited regulation by the Financial Conduct Authority (FCA) and PRA.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 80895,\n" +
            "      \"end\" : 81646\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Details of the extent of our regulation by the PRA, and FCA are available from us on request.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 81816,\n" +
            "      \"end\" : 82022\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Our PRA and FCA registration number is 220041.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 82726,\n" +
            "      \"end\" : 82744\n" +
            "    },\n" +
            "    \"sentence_text\" : \"POLICY INFORMATION\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 83006,\n" +
            "      \"end\" : 83024\n" +
            "    },\n" +
            "    \"sentence_text\" : \"THE POLICY WORDING\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 83219,\n" +
            "      \"end\" : 83664\n" +
            "    },\n" +
            "    \"sentence_text\" : \"The Policy Wording tells you exactly what is and is not covered, how to make a claim and other important information.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 83928,\n" +
            "      \"end\" : 83943\n" +
            "    },\n" +
            "    \"sentence_text\" : \"POLICY SCHEDULE\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 85259,\n" +
            "      \"end\" : 85469\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Please keep it with the Policy Wording.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 85735,\n" +
            "      \"end\" : 85765\n" +
            "    },\n" +
            "    \"sentence_text\" : \"GOVERNING LAW AND JURISDICTION\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 87729,\n" +
            "      \"end\" : 87748\n" +
            "    },\n" +
            "    \"sentence_text\" : \"DATA PROTECTION ACT\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 89815,\n" +
            "      \"end\" : 89843\n" +
            "    },\n" +
            "    \"sentence_text\" : \"FULL AND ACCURATE DISCLOSURE\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 90037,\n" +
            "      \"end\" : 91699\n" +
            "    },\n" +
            "    \"sentence_text\" : \"It is your responsibility to provide complete and accurate information in response to our questions when you take out your insurance policy, and throughout the life of your policy.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 91860,\n" +
            "      \"end\" : 92123\n" +
            "    },\n" +
            "    \"sentence_text\" : \"See Important Information relating to Health, Activities and the Acceptance of Your Insurance.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 94045,\n" +
            "      \"end\" : 94836\n" +
            "    },\n" +
            "    \"sentence_text\" : \"It is important that you ensure that all statements you make on the application form, claim forms and other documents are full and accurate.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 96639,\n" +
            "      \"end\" : 96656\n" +
            "    },\n" +
            "    \"sentence_text\" : \"ABOUT YOUR POLICY\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 96860,\n" +
            "      \"end\" : 97471\n" +
            "    },\n" +
            "    \"sentence_text\" : \"We know that insurance policies can sometimes be difficult to understand, so we have tried to make this Policy easy to read.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 97642,\n" +
            "      \"end\" : 98267\n" +
            "    },\n" +
            "    \"sentence_text\" : \"We have still had to use some words with special meanings and these are listed and explained on pages 5-7 and will appear in bold in the text.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Obligation\",\n" +
            "        \"party\" : \"We\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NP2P8gvAc69bSB+ho9JenmciHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 99625,\n" +
            "      \"end\" : 99636\n" +
            "    },\n" +
            "    \"sentence_text\" : \"THE INSURER\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 99831,\n" +
            "      \"end\" : 100768\n" +
            "    },\n" +
            "    \"sentence_text\" : \"This Policy is underwritten by ETI - International Travel Protection, the UK branch of Europäische Reiseversicherung A.G., ( ERV ) an Ergo Group Company incorporated and regulated under the laws of the Germany, Companies House Registration FC 25660 and Branch Registration BR 007939.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Location\",\n" +
            "      \"text\" : \"Germany\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 100687,\n" +
            "        \"end\" : 100694\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 102126,\n" +
            "      \"end\" : 102153\n" +
            "    },\n" +
            "    \"sentence_text\" : \"RECIPROCAL HEALTH AGREEMENT\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 102346,\n" +
            "      \"end\" : 103476\n" +
            "    },\n" +
            "    \"sentence_text\" : \"If you are travelling to a European Union country you are strongly advised to obtain a European Health Insurance Card from your local post office.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 103477,\n" +
            "      \"end\" : 103912\n" +
            "    },\n" +
            "    \"sentence_text\" : \"This will entitle you to benefit from the reciprocal health agreements, which exist between EU countries.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Right\",\n" +
            "        \"party\" : \"You\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NB6GzkcAJzvuiapx9XWXwXLBbYvSRto+2wQVzXSDgLAs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJY2iZEsIRez8saDb5Ft02vg=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 103913,\n" +
            "      \"end\" : 104342\n" +
            "    },\n" +
            "    \"sentence_text\" : \"If you require medical treatment in Australia or New Zealand reciprocal arrangements may also apply.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ {\n" +
            "      \"type\" : \"Location\",\n" +
            "      \"text\" : \"Australia\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 104278,\n" +
            "        \"end\" : 104287\n" +
            "      }\n" +
            "    }, {\n" +
            "      \"type\" : \"Location\",\n" +
            "      \"text\" : \"New Zealand\",\n" +
            "      \"attribute\" : {\n" +
            "        \"begin\" : 104291,\n" +
            "        \"end\" : 104302\n" +
            "      }\n" +
            "    } ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 104599,\n" +
            "      \"end\" : 104616\n" +
            "    },\n" +
            "    \"sentence_text\" : \"POLICY CONDITIONS\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 104812,\n" +
            "      \"end\" : 105563\n" +
            "    },\n" +
            "    \"sentence_text\" : \"These are the conditions of the insurance you will need to meet as your part of this contract.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Obligation\",\n" +
            "        \"party\" : \"You\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NP2P8gvAc69bSB+ho9JenmciHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJY2iZEsIRez8saDb5Ft02vg=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"NNtzdPi0PFR+R0Gs6JgoYuk04j4A/cYGPkRk6uzIdLjU=\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 105564,\n" +
            "      \"end\" : 105659\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Certain sections of cover have certain additional conditions, which must also be complied with.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 105913,\n" +
            "      \"end\" : 105927\n" +
            "    },\n" +
            "    \"sentence_text\" : \"AGE LIMITATION\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 106123,\n" +
            "      \"end\" : 106405\n" +
            "    },\n" +
            "    \"sentence_text\" : \"For single trip policies, cover does not extend to any person aged over 74 at commencement of the Policy Period.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 106565,\n" +
            "      \"end\" : 106849\n" +
            "    },\n" +
            "    \"sentence_text\" : \"For annual multi trip policies, there is no cover for any person aged over 69 at commencement of the Policy Period.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 107115,\n" +
            "      \"end\" : 107136\n" +
            "    },\n" +
            "    \"sentence_text\" : \"CANCELLING THE POLICY\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 107340,\n" +
            "      \"end\" : 108688\n" +
            "    },\n" +
            "    \"sentence_text\" : \"You may cancel this Policy within 14 days of its issue (provided you have not commenced an Insured Journey ) and, subject to you not having or intending to make a claim, a full refund of premium will be made.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Right\",\n" +
            "        \"party\" : \"You\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NFcxTEbqRNqPlGopeAMxC8YOFjt/T/aNeYQ61ZTKoNOU=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PYbT9wLBuxyNYn0MkwFABJY2iZEsIRez8saDb5Ft02vg=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 110355,\n" +
            "      \"end\" : 111336\n" +
            "    },\n" +
            "    \"sentence_text\" : \"We may cancel this Policy by giving you at least 30 days' notice (or in the event of non-payment of premium, seven days' notice) in writing at your last known address.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Right\",\n" +
            "        \"party\" : \"We\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"NFcxTEbqRNqPlGopeAMxC8YOFjt/T/aNeYQ61ZTKoNOU=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 111337,\n" +
            "      \"end\" : 112425\n" +
            "    },\n" +
            "    \"sentence_text\" : \"If we do, the premium you have paid for the rest of the current Policy Period will be refunded pro rata.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 112691,\n" +
            "      \"end\" : 112712\n" +
            "    },\n" +
            "    \"sentence_text\" : \"COMMENCEMENT OF COVER\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 117152,\n" +
            "      \"end\" : 117173\n" +
            "    },\n" +
            "    \"sentence_text\" : \"DOMESTIC TRAVEL COVER\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 117368,\n" +
            "      \"end\" : 118569\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Domestic holidays (within your country of residence) that include a flight or pre-booked overnight Accommodation away from your normal place of residence, are covered subject to all other policy terms and conditions.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 118833,\n" +
            "      \"end\" : 118847\n" +
            "    },\n" +
            "    \"sentence_text\" : \"FAMILY MEMBERS\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 119670,\n" +
            "      \"end\" : 120397\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Children are only covered when travelling with you or your spouse or partner.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 120661,\n" +
            "      \"end\" : 120677\n" +
            "    },\n" +
            "    \"sentence_text\" : \"MAXIMUM DURATION\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 121311,\n" +
            "      \"end\" : 121391\n" +
            "    },\n" +
            "    \"sentence_text\" : \"Any one trip covered by a single trip policy is limited to a maximum of 90 days.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 121392,\n" +
            "      \"end\" : 121777\n" +
            "    },\n" +
            "    \"sentence_text\" : \"These limits vary with age and the options you have chosen.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 123242,\n" +
            "      \"end\" : 123260\n" +
            "    },\n" +
            "    \"sentence_text\" : \"GENERAL EXCLUSIONS\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 123474,\n" +
            "      \"end\" : 123533\n" +
            "    },\n" +
            "    \"sentence_text\" : \"General exclusions apply to all sections of this insurance.\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 123742,\n" +
            "      \"end\" : 123927\n" +
            "    },\n" +
            "    \"sentence_text\" : \"In addition to these general exclusions, please also refer to 'What you are not covered for' under each policy section as this sets out further exclusions that apply to certain sections\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 124135,\n" +
            "      \"end\" : 124167\n" +
            "    },\n" +
            "    \"sentence_text\" : \"We will not cover the following.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Exclusion\",\n" +
            "        \"party\" : \"We\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"Nwmnnl4K38f5WdJYzxfCOhIm2UpLLK+PkLO0/wzF0x/w=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3YsiHE+o/KFxVziGPT72ApSs=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e15pHZNmMV0bXWDDRtYu1x0=\"\n" +
            "      }, {\n" +
            "        \"id\" : \"P++F/u+PzCtvIoJOcaLcSNg==\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 124410,\n" +
            "      \"end\" : 124650\n" +
            "    },\n" +
            "    \"sentence_text\" : \"1. Any claim that relates to a known medical condition\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Exclusion\",\n" +
            "        \"party\" : \"We\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"Nwmnnl4K38f5WdJYzxfCOhHqOdvM3XCiFlka0oDaESJr+oAGBnPQFBDQY+ZsVTD0g\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3Yg3J53G1OKrVtdwiRa2A66YwznuIZKl37/4YxKfAOocD\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e4DBD1HjkE8/fhd4g2slwWlW4Hf+L0fXmrlNT68XGiuFQF5YV8X33w5hCI9hWIPbIg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"Pqjd5I+s/Fdpx2NbIwCRMtztzc5JdzB+aHfojSiMbztZp4u1DZxlwdAHWePYv+wfs\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 124859,\n" +
            "      \"end\" : 125239\n" +
            "    },\n" +
            "    \"sentence_text\" : \"2. Any claim if you, or any person whose condition may give rise to a claim, are suffering from or have suffered from any diagnosed psychological or psychiatric disorder, anxiety or depression.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Exclusion\",\n" +
            "        \"party\" : \"We\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"Nwmnnl4K38f5WdJYzxfCOhHqOdvM3XCiFlka0oDaESJr+oAGBnPQFBDQY+ZsVTD0g\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3Yg3J53G1OKrVtdwiRa2A66YwznuIZKl37/4YxKfAOocD\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e4DBD1HjkE8/fhd4g2slwWlW4Hf+L0fXmrlNT68XGiuFQF5YV8X33w5hCI9hWIPbIg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"Pqjd5I+s/Fdpx2NbIwCRMtztzc5JdzB+aHfojSiMbztZp4u1DZxlwdAHWePYv+wfs\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 125448,\n" +
            "      \"end\" : 125715\n" +
            "    },\n" +
            "    \"sentence_text\" : \"3. Any claims arising as a result of flying less than 24 hours after a scuba dive\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Exclusion\",\n" +
            "        \"party\" : \"We\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"Nwmnnl4K38f5WdJYzxfCOhHqOdvM3XCiFlka0oDaESJr+oAGBnPQFBDQY+ZsVTD0g\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3Yg3J53G1OKrVtdwiRa2A66YwznuIZKl37/4YxKfAOocD\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e4DBD1HjkE8/fhd4g2slwWlW4Hf+L0fXmrlNT68XGiuFQF5YV8X33w5hCI9hWIPbIg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"Pqjd5I+s/Fdpx2NbIwCRMtztzc5JdzB+aHfojSiMbztZp4u1DZxlwdAHWePYv+wfs\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 125924,\n" +
            "      \"end\" : 126252\n" +
            "    },\n" +
            "    \"sentence_text\" : \"4. Any injury/accidents related to scuba diving unless you have paid the necessary premium to extend your insurance to provide cover for this.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Exclusion\",\n" +
            "        \"party\" : \"We\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"Nwmnnl4K38f5WdJYzxfCOhHqOdvM3XCiFlka0oDaESJr+oAGBnPQFBDQY+ZsVTD0g\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3Yg3J53G1OKrVtdwiRa2A66YwznuIZKl37/4YxKfAOocD\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e4DBD1HjkE8/fhd4g2slwWlW4Hf+L0fXmrlNT68XGiuFQF5YV8X33w5hCI9hWIPbIg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"Pqjd5I+s/Fdpx2NbIwCRMtztzc5JdzB+aHfojSiMbztZp4u1DZxlwdAHWePYv+wfs\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 126461,\n" +
            "      \"end\" : 126734\n" +
            "    },\n" +
            "    \"sentence_text\" : \"5. Any claim arising out of war, civil war, invasion, revolution or any similar event.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Exclusion\",\n" +
            "        \"party\" : \"We\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"Nwmnnl4K38f5WdJYzxfCOhHqOdvM3XCiFlka0oDaESJr+oAGBnPQFBDQY+ZsVTD0g\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3Yg3J53G1OKrVtdwiRa2A66YwznuIZKl37/4YxKfAOocD\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e4DBD1HjkE8/fhd4g2slwWlW4Hf+L0fXmrlNT68XGiuFQF5YV8X33w5hCI9hWIPbIg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"Pqjd5I+s/Fdpx2NbIwCRMtztzc5JdzB+aHfojSiMbztZp4u1DZxlwdAHWePYv+wfs\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 126943,\n" +
            "      \"end\" : 127324\n" +
            "    },\n" +
            "    \"sentence_text\" : \"6. Any claim arising from using a two-wheeled motor vehicle over 50cc as a driver or passenger if you are not wearing a crash helmet, or the driver does not hold an appropriate driving licence.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Exclusion\",\n" +
            "        \"party\" : \"We\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"Nwmnnl4K38f5WdJYzxfCOhHqOdvM3XCiFlka0oDaESJr+oAGBnPQFBDQY+ZsVTD0g\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3Yg3J53G1OKrVtdwiRa2A66YwznuIZKl37/4YxKfAOocD\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e4DBD1HjkE8/fhd4g2slwWlW4Hf+L0fXmrlNT68XGiuFQF5YV8X33w5hCI9hWIPbIg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"Pqjd5I+s/Fdpx2NbIwCRMtztzc5JdzB+aHfojSiMbztZp4u1DZxlwdAHWePYv+wfs\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 127533,\n" +
            "      \"end\" : 127774\n" +
            "    },\n" +
            "    \"sentence_text\" : \"7. Motor racing, rallying or vehicle racing of any kind.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Exclusion\",\n" +
            "        \"party\" : \"We\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"Nwmnnl4K38f5WdJYzxfCOhHqOdvM3XCiFlka0oDaESJr+oAGBnPQFBDQY+ZsVTD0g\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3Yg3J53G1OKrVtdwiRa2A66YwznuIZKl37/4YxKfAOocD\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e4DBD1HjkE8/fhd4g2slwWlW4Hf+L0fXmrlNT68XGiuFQF5YV8X33w5hCI9hWIPbIg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"Pqjd5I+s/Fdpx2NbIwCRMtztzc5JdzB+aHfojSiMbztZp4u1DZxlwdAHWePYv+wfs\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 127983,\n" +
            "      \"end\" : 128330\n" +
            "    },\n" +
            "    \"sentence_text\" : \"8. Any claim arising from you being in, entering, or leaving any aircraft other than as a fare-paying passenger in a fully-licensed passenger-carrying aircraft.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Exclusion\",\n" +
            "        \"party\" : \"We\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"Nwmnnl4K38f5WdJYzxfCOhHqOdvM3XCiFlka0oDaESJr+oAGBnPQFBDQY+ZsVTD0g\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3Yg3J53G1OKrVtdwiRa2A66YwznuIZKl37/4YxKfAOocD\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e4DBD1HjkE8/fhd4g2slwWlW4Hf+L0fXmrlNT68XGiuFQF5YV8X33w5hCI9hWIPbIg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"Pqjd5I+s/Fdpx2NbIwCRMtztzc5JdzB+aHfojSiMbztZp4u1DZxlwdAHWePYv+wfs\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 128540,\n" +
            "      \"end\" : 128860\n" +
            "    },\n" +
            "    \"sentence_text\" : \"9. Any claim relating to winter sports unless you have paid the necessary premium to extend your insurance to provide cover for this.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Exclusion\",\n" +
            "        \"party\" : \"We\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"Nwmnnl4K38f5WdJYzxfCOhHqOdvM3XCiFlka0oDaESJr+oAGBnPQFBDQY+ZsVTD0g\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3Yg3J53G1OKrVtdwiRa2A66YwznuIZKl37/4YxKfAOocD\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e4DBD1HjkE8/fhd4g2slwWlW4Hf+L0fXmrlNT68XGiuFQF5YV8X33w5hCI9hWIPbIg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"Pqjd5I+s/Fdpx2NbIwCRMtztzc5JdzB+aHfojSiMbztZp4u1DZxlwdAHWePYv+wfs\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 129069,\n" +
            "      \"end\" : 129360\n" +
            "    },\n" +
            "    \"sentence_text\" : \"10. Any claim arising as a result of you failing to get the inoculations and vaccinations that you need.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Exclusion\",\n" +
            "        \"party\" : \"We\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"Nwmnnl4K38f5WdJYzxfCOhHqOdvM3XCiFlka0oDaESJr+oAGBnPQFBDQY+ZsVTD0g\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3Yg3J53G1OKrVtdwiRa2A66YwznuIZKl37/4YxKfAOocD\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e4DBD1HjkE8/fhd4g2slwWlW4Hf+L0fXmrlNT68XGiuFQF5YV8X33w5hCI9hWIPbIg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"Pqjd5I+s/Fdpx2NbIwCRMtztzc5JdzB+aHfojSiMbztZp4u1DZxlwdAHWePYv+wfs\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 129569,\n" +
            "      \"end\" : 129960\n" +
            "    },\n" +
            "    \"sentence_text\" : \"11. Any claim, loss, injury, damage or legal liability arising directly or indirectly from any planned or actual trip in, to or through Cuba, Iran, Sudan, Syria, Crimea region of Ukraine and North Korea.\",\n" +
            "    \"types\" : [ {\n" +
            "      \"label\" : {\n" +
            "        \"nature\" : \"Exclusion\",\n" +
            "        \"party\" : \"We\"\n" +
            "      },\n" +
            "      \"assurance\" : \"High\",\n" +
            "      \"provenance\" : [ {\n" +
            "        \"id\" : \"Nwmnnl4K38f5WdJYzxfCOhHqOdvM3XCiFlka0oDaESJr+oAGBnPQFBDQY+ZsVTD0g\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PRVWtrGWZh8dDV6bbsme3Yg3J53G1OKrVtdwiRa2A66YwznuIZKl37/4YxKfAOocD\"\n" +
            "      }, {\n" +
            "        \"id\" : \"PHkFRgCotU1vpcGBwHgj3e4DBD1HjkE8/fhd4g2slwWlW4Hf+L0fXmrlNT68XGiuFQF5YV8X33w5hCI9hWIPbIg==\"\n" +
            "      }, {\n" +
            "        \"id\" : \"Pqjd5I+s/Fdpx2NbIwCRMtztzc5JdzB+aHfojSiMbztZp4u1DZxlwdAHWePYv+wfs\"\n" +
            "      } ]\n" +
            "    } ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  }, {\n" +
            "    \"sentence\" : {\n" +
            "      \"begin\" : 130216,\n" +
            "      \"end\" : 130243\n" +
            "    },\n" +
            "    \"sentence_text\" : \"RIGHTS AND RESPONSIBILITIES\",\n" +
            "    \"types\" : [ ],\n" +
            "    \"categories\" : [ ],\n" +
            "    \"attributes\" : [ ]\n" +
            "  } ],\n" +
            "  \"parties\" : [ {\n" +
            "    \"party\" : \"Buyer\",\n" +
            "    \"role\" : \"Buyer\"\n" +
            "  }, {\n" +
            "    \"party\" : \"Supplier\",\n" +
            "    \"role\" : \"Supplier\"\n" +
            "  }, {\n" +
            "    \"party\" : \"We\",\n" +
            "    \"role\" : \"Unknown\"\n" +
            "  }, {\n" +
            "    \"party\" : \"You\",\n" +
            "    \"role\" : \"Unknown\"\n" +
            "  } ]\n" +
            "}\n";
}