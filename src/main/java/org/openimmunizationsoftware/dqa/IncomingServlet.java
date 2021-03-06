/*
 * Copyright 2013 by Dandelion Software & Research, Inc (DSR)
 * 
 * This application was written for immunization information system (IIS) community and has
 * been released by DSR under an Apache 2 License with the hope that this software will be used
 * to improve Public Health.  
 */
package org.openimmunizationsoftware.dqa;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.openimmunizationsoftware.dqa.db.model.Application;
import org.openimmunizationsoftware.dqa.db.model.BatchReport;
import org.openimmunizationsoftware.dqa.db.model.BatchType;
import org.openimmunizationsoftware.dqa.db.model.CodeReceived;
import org.openimmunizationsoftware.dqa.db.model.CodeTable;
import org.openimmunizationsoftware.dqa.db.model.IssueFound;
import org.openimmunizationsoftware.dqa.db.model.MessageBatch;
import org.openimmunizationsoftware.dqa.db.model.MessageReceivedGeneric;
import org.openimmunizationsoftware.dqa.db.model.Organization;
import org.openimmunizationsoftware.dqa.db.model.ReportTemplate;
import org.openimmunizationsoftware.dqa.db.model.SubmitterProfile;
import org.openimmunizationsoftware.dqa.db.model.UserAccount;
import org.openimmunizationsoftware.dqa.manager.CodesReceived;
import org.openimmunizationsoftware.dqa.manager.DatabaseCleanupManager;
import org.openimmunizationsoftware.dqa.manager.FileImportManager;
import org.openimmunizationsoftware.dqa.manager.OrganizationManager;
import org.openimmunizationsoftware.dqa.manager.SubmissionManager;
import org.openimmunizationsoftware.dqa.manager.WeeklyBatchManager;
import org.openimmunizationsoftware.dqa.manager.WeeklyExportManager;
import org.openimmunizationsoftware.dqa.parse.PrintBean;
import org.openimmunizationsoftware.dqa.parse.VaccinationParserHL7;
import org.openimmunizationsoftware.dqa.process.MessageProcessRequest;
import org.openimmunizationsoftware.dqa.process.MessageProcessResponse;
import org.openimmunizationsoftware.dqa.process.MessageProcessor;
import org.openimmunizationsoftware.dqa.quality.QualityCollector;
import org.openimmunizationsoftware.dqa.transport.SubmitSingleMessage;

public class IncomingServlet extends HttpServlet
{

  protected static final String SUBMITTER_PROFILE = "submitterProfile";
  protected static FileImportManager fileImportManager = null;
  protected static SubmissionManager submissionManager = null;
  protected static WeeklyBatchManager weeklyBatchManager = null;
  protected static WeeklyExportManager weeklyExportManager = null;
  protected static DatabaseCleanupManager databaseCleanupManager = null;

  @Override
  public void init() throws ServletException
  {
    fileImportManager = FileImportManager.getFileImportManager();
    submissionManager = SubmissionManager.getSubmissionManager();
    weeklyBatchManager = WeeklyBatchManager.getWeeklyBatchManager();
    weeklyExportManager = WeeklyExportManager.getWeeklyExportManager();
    databaseCleanupManager = DatabaseCleanupManager.getDatabaseCleanupManager();
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
  {
    resp.setContentType("text/html");
    PrintWriter out = new PrintWriter(resp.getOutputStream());
    out.println("<html>");
    out.println("  <head>");
    out.println("    <title>DQA Incoming Interface</title>");
    out.println("  </head>");
    out.println("  <body>");
    out.println("    <h1>DQA Incoming Interface</h1>");
    out.println("    <form action=\"in\" method=\"POST\">");
    out.println("      <table border=\"0\">");
    out.println("        <tr>");
    out.println("          <td><code>USERID</code></td>");
    out.println("          <td><input name=\"USERID\" type=\"text\" value=\"\"></td>");
    out.println("        </tr>");
    out.println("        <tr>");
    out.println("          <td><code>PASSWORD</code></td>");
    out.println("          <td><input name=\"PASSWORD\" type=\"text\" value=\"\"></td>");
    out.println("        </tr>");
    out.println("        <tr>");
    out.println("          <td><code>FACILITYID</code></td>");
    out.println("          <td><input name=\"FACILITYID\" type=\"text\" value=\"\"></td>");
    out.println("        </tr>");
    out.println("      </table>");
    out.println("      <table border=\"0\">");
    out.println("        <tr>");
    out.println("          <td><code>MESSAGEDATA</code></td>");
    out.println("        </tr>");
    out.println("        <tr>");
    out.println("          <td><textarea name=\"MESSAGEDATA\" cols=\"120\" rows=\"15\"></textarea></td>");
    out.println("        </tr>");
    out.println("      </table>");
    out.println("      <input type=\"checkbox\" name=\"DEBUG\" value=\"T\">Debug");
    out.println("      <input name=\"submit\" value=\"Submit\" type=\"submit\">");
    out.println("    </form>");
    out.println("    ");

    out.println("    <hr>");
    out.println("    <p>Version " + SoftwareVersion.VERSION + " released " + SoftwareVersion.RELEASE_DATE + "</p>");
    out.println("  </body>");
    out.println("</html>");
    out.close();
    out = null;
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
  {
    SubmitSingleMessage ssm = new SubmitSingleMessage();

    ssm.setUsername(req.getParameter("USERID"));
    ssm.setPassword(req.getParameter("PASSWORD"));
    ssm.setFacilityID(req.getParameter("FACILITYID"));
    ssm.setDebug(req.getParameter("DEBUG") != null);
    ssm.setHl7Message(req.getParameter("MESSAGEDATA"));

    resp.setContentType("text/plain");
    PrintWriter out = new PrintWriter(resp.getOutputStream());

    SessionFactory factory = OrganizationManager.getSessionFactory();
    Session session = factory.openSession();
    authorize(ssm, session);
    if (ssm.getAccessDenied() == null)
    {
      processMessage(ssm, out, session);
    } else
    {
      out.println(ssm.getAccessDenied());
    }
    if (ssm.isDebug())
    {
      // printDebugOutput(out, session, profile, qualityCollector);
    }
    out.close();
    out = null;
    session.flush();
    session.close();
  }

  public static void processMessage(SubmitSingleMessage ssm, PrintWriter out, Session session) throws IOException
  {
    SubmitterProfile submitterProfile = (SubmitterProfile) ssm.getAttribute(SUBMITTER_PROFILE);
    try
    {
      ProcessLocker.lock(submitterProfile);
      QualityCollector qualityCollector = new QualityCollector("Realtime HTTPS", BatchType.SUBMISSION, submitterProfile);
      // Results results = new Results();
      // results.setDebug(debug);
      // processStream(debug, session, profile, messageData, out,
      // qualityCollector, results);
      ssm.setDebug(processStream(ssm.isDebug(), session, submitterProfile, ssm.getHl7Message(), out, qualityCollector));
      // debug = results.isDebug();
    } finally
    {
      ProcessLocker.unlock(submitterProfile);
    }
  }

  public static void authorize(SubmitSingleMessage ssm, Session session)
  {
    Transaction tx = session.beginTransaction();
    if (ssm.getUsername() == null || ssm.getUsername().equals(""))
    {
      ssm.setAccessDenied("USERID was not specified and is required for real time submission");
    } else if (ssm.getPassword() == null || ssm.getPassword().equals(""))
    {
      ssm.setAccessDenied("PASSWORD was not specified and is required for real time submission");
    } else if (ssm.getFacilityID() == null || ssm.getFacilityID().equals(""))
    {
      ssm.setAccessDenied("FACILITYID was not specified and is required for real time submission");
    }
    if (ssm.getAccessDenied() == null)
    {
      boolean isEmail = false;
      int pos = ssm.getUsername().indexOf("@");
      isEmail = pos > 1 && pos < (ssm.getUsername().length() - 2);
      String emailUsername = "";
      String emailHost = "";
      if (isEmail)
      {
        emailUsername = ssm.getUsername().substring(0, pos);
        emailHost = ssm.getUsername().substring(pos + 1);
      }
      SubmitterProfile submitterProfile = null;
      if (isEmail)
      {
        Query query = session.createQuery("from UserAccount where email = ?");
        query.setParameter(0, ssm.getUsername());
        List<UserAccount> userAccounts = query.list();
        UserAccount userAccount = null;
        if (userAccounts.size() > 0)
        {
          userAccount = userAccounts.get(0);
        } else
        {
          query = session.createQuery("from Organization where orgLocalCode = ?");
          query.setParameter(0, emailHost); // Use email host as the
                                            // organization name
          List<Organization> organizations = query.list();
          Organization organization = null;
          if (organizations.size() == 0)
          {
            organization = new Organization();
            organization.setOrgLabel(emailHost);
            organization.setOrgLocalCode(emailHost);
            organization.setParentOrganization((Organization) session.get(Organization.class, 1));
            session.save(organization);
          } else
          {
            organization = organizations.get(0);
          }
          // find unique user name
          String username = null;
          int i = 0;
          while (username == null)
          {
            username = (i == 0 ? emailUsername : emailUsername + i);
            query = session.createQuery("from UserAccount where username = ?");
            query.setParameter(0, username);
            if (query.list().size() > 0)
            {
              username = null;
              i++;
            }
          }
          userAccount = new UserAccount();
          userAccount.setAccountType(UserAccount.ACCOUNT_TYPE_SUBMITTER);
          userAccount.setEmail(ssm.getUsername());
          userAccount.setUsername(username);
          userAccount.setOrganization(organization);
          userAccount.setPassword(ssm.getPassword());
          session.save(userAccount);
        }
        
        query = session.createQuery("from SubmitterProfile where organization = ? and profileLabel = ?");
        query.setParameter(0, userAccount.getOrganization());
        query.setParameter(1, ssm.getFacilityID());
        List<SubmitterProfile> submitterProfiles = query.list();
        if (submitterProfiles.size() == 0)
        {
          submitterProfile = new SubmitterProfile();
          ssm.setAttribute(SUBMITTER_PROFILE, submitterProfile);
          submitterProfile.setAccessKey(ssm.getPassword());
          submitterProfile.setDataFormat(SubmitterProfile.DATA_FORMAT_HL7V2);
          submitterProfile.setOrganization(userAccount.getOrganization());
          submitterProfile.setProfileCode(ssm.getFacilityID());
          submitterProfile.setProfileLabel(ssm.getFacilityID());
          submitterProfile.setProfileStatus(SubmitterProfile.PROFILE_STATUS_TEST);
          submitterProfile.setTransferPriority(SubmitterProfile.TRANSFER_PRIORITY_NORMAL);
          query = session.createQuery("from Application where runThis = 'Y'");
          List<Application> applicationList = query.list();
          if (applicationList.size() > 0)
          {
            Application application = applicationList.get(0);
            submitterProfile.setReportTemplate(application.getPrimaryReportTemplate());
          } else
          {
            submitterProfile.setReportTemplate((ReportTemplate) session.get(ReportTemplate.class, 1));
          }

          session.save(submitterProfile);
        } else
        {
          submitterProfile = submitterProfiles.get(0);
          if (!submitterProfile.getAccessKey().equals(ssm.getPassword()))
          {
            ssm.setAccessDenied("Unrecognized USERID, PASSWORD or FACILITYID");
          }
          else
          {
            ssm.setAttribute(SUBMITTER_PROFILE, submitterProfile);
          }
        }
      } else
      {
        Query query = session.createQuery("from SubmitterProfile where profileCode = ? and accessKey = ?");
        query.setParameter(0, ssm.getUsername());
        query.setParameter(1, ssm.getPassword());
        List<SubmitterProfile> submitterProfiles = query.list();
        if (submitterProfiles.size() == 0)
        {
          ssm.setAccessDenied("Authorization failed, invalid USERID or PASSWORD");
        } else
        {
          submitterProfile = submitterProfiles.get(0);
          ssm.setAttribute(SUBMITTER_PROFILE, submitterProfile);
        }
      }
    }
    tx.commit();
  }

  public static void printDebugOutput(PrintWriter out, Session session, SubmitterProfile profile, QualityCollector qualityCollector)
  {
    if (qualityCollector != null)
    {
      printMessageBatch(out, qualityCollector);
    }
    out.print("\r");
    out.print("\r");
    CodesReceived cr = CodesReceived.getCodesReceived(profile, session);
    CodesReceived masterCr = CodesReceived.getCodesReceived();
    for (CodeTable codeTable : cr.getCodeTableList())
    {
      List<CodeReceived> codesReceived = cr.getCodesReceived(codeTable);
      if (codesReceived.size() > 0)
      {
        out.print("-- " + padSlash(codeTable.getTableLabel() + " ", 92) + "\r");
        out.print("\r");
        out.print("VALUES RECEIVED\r");
        out.print(pad("value", 20));
        out.print(pad("label", 30));
        out.print(pad("use instead", 20));
        out.print(pad("status", 15));
        out.print(pad("count", 7));
        out.print("\r");
        for (CodeReceived codeReceived : codesReceived)
        {
          out.print(pad(codeReceived.getReceivedValue(), 20));
          out.print(pad(codeReceived.getCodeLabel(), 30));
          if (codeReceived.getCodeValue() == null || (codeReceived.getCodeValue().equals(codeReceived.getReceivedValue())))
          {
            out.print(pad("", 20));
          } else
          {
            out.print(pad(codeReceived.getCodeValue(), 20));
          }
          out.print(pad(codeReceived.getCodeStatus().getCodeLabel(), 15));
          out.print(pad(String.valueOf(codeReceived.getReceivedCount()), 7));
          out.print("\r");
        }
        codesReceived = masterCr.getCodesReceived(codeTable);
        out.print("\r");
        out.print("MASTER VALUE LIST\r");
        out.print(pad("value", 20));
        out.print(pad("label", 30));
        out.print(pad("use instead", 20));
        out.print(pad("status", 15));
        out.print("\r");

        for (CodeReceived codeReceived : codesReceived)
        {
          out.print(pad(codeReceived.getReceivedValue(), 20));
          out.print(pad(codeReceived.getCodeLabel(), 30));
          if (codeReceived.getCodeValue() == null || (codeReceived.getCodeValue().equals(codeReceived.getReceivedValue())))
          {
            out.print(pad("", 20));
          } else
          {
            out.print(pad(codeReceived.getCodeValue(), 20));
          }
          out.print(pad(codeReceived.getCodeStatus().getCodeLabel(), 15));
          out.print("\r");
        }
        out.print("\r");
      }
    }
  }

  private static void printMessageBatch(PrintWriter out, QualityCollector qualityCollector)
  {
    MessageBatch mb = qualityCollector.getMessageBatch();
    BatchReport r = mb.getBatchReport();
    out.print("\r");
    out.print("\r");
    out.print("Message Batch Summary: \r");
    out.print("Message Count:       " + r.getMessageCount() + "\r");
    out.print("Patient Count:       " + r.getMessageCount() + "\r");
    out.print("Vaccination      \r");
    out.print(" + Administered:     " + r.getVaccinationAdministeredCount() + "\r");
    out.print(" + Historical:       " + r.getVaccinationHistoricalCount() + "\r");
    out.print(" + Not Administered: " + r.getVaccinationNotAdministeredCount() + "\r");
    out.print(" + Deleted:          " + r.getVaccinationDeleteCount() + "\r");
  }

  /*
   * public static class Results { private boolean debug = false; private String
   * responseStatus = ""; private String responseText = ""; private int batchId
   * = 0; private int receivedId = 0;
   * 
   * private List<IssueType> errorList = null; private List<IssueType>
   * warningList = null;
   * 
   * public List<IssueType> getErrorList() { return errorList; }
   * 
   * public void setErrorList(List<IssueType> errorList) { this.errorList =
   * errorList; }
   * 
   * public List<IssueType> getWarningList() { return warningList; }
   * 
   * public void setWarningList(List<IssueType> warningList) { this.warningList
   * = warningList; }
   * 
   * public boolean isDebug() { return debug; }
   * 
   * public void setDebug(boolean debug) { this.debug = debug; }
   * 
   * public String getResponseStatus() { return responseStatus; }
   * 
   * public void setResponseStatus(String responseStatus) { this.responseStatus
   * = responseStatus; }
   * 
   * public String getResponseText() { return responseText; }
   * 
   * public void setResponseText(String responseText) { this.responseText =
   * responseText; }
   * 
   * public int getBatchId() { return batchId; }
   * 
   * public void setBatchId(int batchId) { this.batchId = batchId; }
   * 
   * public int getReceivedId() { return receivedId; }
   * 
   * public void setReceivedId(int receivedId) { this.receivedId = receivedId; }
   * }
   */
  // public static void processStream(boolean debug, Session session,
  // SubmitterProfile profile, String messageData, PrintWriter out,
  // QualityCollector qualityCollector, Results results) throws IOException
  public static boolean processStream(boolean debug, Session session, SubmitterProfile profile, String messageData, PrintWriter out,
      QualityCollector qualityCollector) throws IOException
  {
    VaccinationParserHL7 parser = new VaccinationParserHL7(profile);
    boolean debugOn = debug;

    StringReader stringReader = new StringReader(messageData);
    BufferedReader in = new BufferedReader(stringReader);
    String line = null;
    StringBuilder sb = new StringBuilder();
    MessageReceivedGeneric msg = null;

    while ((line = in.readLine()) != null)
    {
      if (line.startsWith("MSH"))
      {
        if (sb.length() > 0)
        {
          MessageProcessRequest request = new MessageProcessRequest(sb.toString());
          request.setDebugFlag(debug);
          request.setParser(parser);
          request.setProfile(profile);
          request.setSession(session);
          request.setQualityCollector(qualityCollector);
          MessageProcessResponse response = MessageProcessor.processMessage(request);
          msg = response.getMessageReceived();
          if (msg.isSuccessfulCompletion())
          {
            printMessage(msg, qualityCollector, out, request);
            debugOn = request.isDebugOn();

          } else
          {
            if (out != null)
            {
              out.print(msg.getResponseText());
            } else
            {
              msg.getException().printStackTrace();
            }
            if (request.isDebugOn())
            {
              msg.getException().printStackTrace(out);
            }
            msg.getException().printStackTrace();
          }

        }
        sb.setLength(0);
      } else if (line.startsWith("FHS") || line.startsWith("BHS") || line.startsWith("BTS") || line.startsWith("FTS"))
      {
        continue;
      }
      sb.append(line);
      sb.append("\r");
    }
    if (sb.length() > 0)
    {
      MessageProcessRequest request = new MessageProcessRequest(sb.toString());
      request.setDebugFlag(debug);
      request.setParser(parser);
      request.setProfile(profile);
      request.setSession(session);
      request.setQualityCollector(qualityCollector);
      MessageProcessResponse response = MessageProcessor.processMessage(request);
      msg = response.getMessageReceived();
      if (msg.isSuccessfulCompletion())
      {
        printMessage(msg, qualityCollector, out, request);
        debugOn = request.isDebugOn();

      } else
      {
        if (out != null)
        {
          out.print(msg.getResponseText());
        } else
        {
          msg.getException().printStackTrace();
        }
        if (request.isDebugOn())
        {
          msg.getException().printStackTrace(out);
        }
        if (msg.getException() != null)
        {
          msg.getException().printStackTrace();
        }
      }
      debug = request.isDebugOn();
    }
    Transaction tx = session.beginTransaction();
    qualityCollector.close();
    session.save(qualityCollector.getMessageBatch());
    session.save(qualityCollector.getMessageBatch().getBatchReport());
    tx.commit();

    return debugOn;
  }

  private static final boolean ALLOW_DEBUG_TO_WORK = false;

  private static void printMessage(MessageReceivedGeneric msg, QualityCollector qualityCollector, PrintWriter out, MessageProcessRequest request)
  {

    out.print(msg.getResponseText());
    if (ALLOW_DEBUG_TO_WORK && request.isDebugOn())
    {
      printDebug(msg, qualityCollector, out);
    }
  }

  public static void printDebug(MessageReceivedGeneric msg, QualityCollector qualityCollector, PrintWriter out)
  {
    try
    {
      out.print("-- DEBUG START -------------------------------------------------------\r");
      out.print("Processed message: " + qualityCollector.getMessageBatch().getBatchReport().getMessageCount() + "\r");
      List<IssueFound> issuesFound = msg.getIssuesFound();
      boolean first = true;
      for (IssueFound issueFound : issuesFound)
      {
        if (issueFound.isError())
        {
          if (first)
          {
            out.print("Errors:\r");
            first = false;
          }
          printIssueFound(issueFound, out);
        }
      }
      first = true;
      for (IssueFound issueFound : issuesFound)
      {
        if (issueFound.isWarn())
        {
          if (first)
          {
            out.print("Warnings:\r");
            first = false;
          }
          printIssueFound(issueFound, out);
        }
      }
      first = true;
      for (IssueFound issueFound : issuesFound)
      {
        if (issueFound.isSkip())
        {
          if (first)
          {
            out.print("Skip:\r");
            first = false;
          }
          printIssueFound(issueFound, out);
        }
      }
      out.print("Message Data: \r");
      PrintBean.print(msg, out);
      out.print("-- DEBUG END ---------------------------------------------------------\r");
    } catch (Exception e)
    {
      e.printStackTrace(out);
    }
  }

  /*
   * private static void populateResults(Results results, MessageReceived
   * messageReceived) { results.setReceivedId((int)
   * messageReceived.getReceivedId());
   * results.setResponseStatus(messageReceived.
   * getIssueAction().getActionCode());
   * results.setResponseText(messageReceived.getResponseText()); if
   * (results.getErrorList() != null && results.getWarningList() != null) { for
   * (IssueFound issueFound : messageReceived.getIssuesFound()) { if
   * (issueFound.isError()) { results.getErrorList().add(new
   * IssueType(issueFound.getIssue().getIssueId(),
   * issueFound.getIssue().getDisplayText())); } else if (issueFound.isWarn()) {
   * results.getWarningList().add(new
   * IssueType(issueFound.getIssue().getIssueId(),
   * issueFound.getIssue().getDisplayText())); } } } }
   */
  private static void printIssueFound(IssueFound issueFound, PrintWriter out)
  {
    out.print("  + ");
    out.print(issueFound.getDisplayText());
    out.print("\r");
  }

  private static final String PAD = "                                                                                                          ";

  private static String pad(String s, int size)
  {
    s += PAD;
    return s.substring(0, size - 1) + " ";
  }

  private static final String PAD_SLASH = "-----------------------------------------------------------------------------------------------------";

  private static String padSlash(String s, int size)
  {
    s += PAD_SLASH;
    return s.substring(0, size - 1) + "-";
  }

}
