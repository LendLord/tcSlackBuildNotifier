package slacknotifications;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.google.gson.Gson;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.json.JsonHierarchicalStreamDriver;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.InputStreamRequestEntity;
import org.apache.commons.httpclient.methods.PostMethod;

import org.apache.commons.httpclient.methods.StringRequestEntity;
import slacknotifications.teamcity.BuildState;
import slacknotifications.teamcity.Loggers;
import slacknotifications.teamcity.payload.content.Commit;
import slacknotifications.teamcity.payload.content.SlackNotificationPayloadContent;


public class SlackNotificationImpl implements SlackNotification {

	private String proxyHost;
	private Integer proxyPort = 0;
	private String proxyUsername;
	private String proxyPassword;
	private String channel;
    private String teamName;
    private String token;
    private String iconUrl;
	private String content;
	private SlackNotificationPayloadContent payload;
	private Integer resultCode;
	private HttpClient client;
	private String filename = "";
	private Boolean enabled = false;
	private Boolean errored = false;
	private String errorReason = "";
	private List<NameValuePair> params;
	private BuildState states;
    private String botName;
    private final static String CONTENT_TYPE = "application/x-www-form-urlencoded";
	
/*	This is a bit mask of states that should trigger a SlackNotification.
 *  All ones (11111111) means that all states will trigger the slacknotifications
 *  We'll set that as the default, and then override if we get a more specific bit mask. */ 
	//private Integer EventListBitMask = BuildState.ALL_ENABLED;
	//private Integer EventListBitMask = Integer.parseInt("0",2);
	
	
	public SlackNotificationImpl(){
		this.client = new HttpClient();
		this.params = new ArrayList<NameValuePair>();
	}
	
	public SlackNotificationImpl(String channel){
		this.channel = channel;
		this.client = new HttpClient();
		this.params = new ArrayList<NameValuePair>();
	}
	
	public SlackNotificationImpl(String channel, String proxyHost, String proxyPort){
		this.channel = channel;
		this.client = new HttpClient();
		this.params = new ArrayList<NameValuePair>();
		if (proxyPort.length() != 0) {
			try {
				this.proxyPort = Integer.parseInt(proxyPort);
			} catch (NumberFormatException ex){
				ex.printStackTrace();
			}
		}
		this.setProxy(proxyHost, this.proxyPort);
	}
	
	public SlackNotificationImpl(String channel, String proxyHost, Integer proxyPort){
		this.channel = channel;
		this.client = new HttpClient();
		this.params = new ArrayList<NameValuePair>();
		this.setProxy(proxyHost, proxyPort);
	}
	
	public SlackNotificationImpl(String channel, SlackNotificationProxyConfig proxyConfig){
		this.channel = channel;
		this.client = new HttpClient();
		this.params = new ArrayList<NameValuePair>();
		setProxy(proxyConfig);
	}

	public void setProxy(SlackNotificationProxyConfig proxyConfig) {
		if ((proxyConfig != null) && (proxyConfig.getProxyHost() != null) && (proxyConfig.getProxyPort() != null)){
			this.setProxy(proxyConfig.getProxyHost(), proxyConfig.getProxyPort());
			if (proxyConfig.getCreds() != null){
				this.client.getState().setProxyCredentials(AuthScope.ANY, proxyConfig.getCreds());
			}
		}
	}
	
	public void setProxy(String proxyHost, Integer proxyPort) {
		this.proxyHost = proxyHost;
		this.proxyPort = proxyPort;
		if (this.proxyHost.length() > 0 && !this.proxyPort.equals(0)) {
			this.client.getHostConfiguration().setProxy(this.proxyHost, this.proxyPort);
		}
	}

	public void setProxyUserAndPass(String username, String password){
		this.proxyUsername = username;
		this.proxyPassword = password;
		if (this.proxyUsername.length() > 0 && this.proxyPassword.length() > 0) {
			this.client.getState().setProxyCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
		}
	}
	
	public void post() throws FileNotFoundException, IOException{
		if ((this.enabled) && (!this.errored)){
            String url = String.format("https://slack.com/api/chat.postMessage?token=%s&username=%s&icon_url=%s&channel=%s&text=%s&pretty=1",
                    this.token,
                    this.botName == null ? "" : URLEncoder.encode(this.botName, "UTF-8"),
                    this.iconUrl == null ? "" : URLEncoder.encode(this.iconUrl, "UTF-8"),
                    this.channel == null ? "" : URLEncoder.encode(this.channel, "UTF-8"),
                    this.payload == null ? "" : URLEncoder.encode(payload.getBuildDescriptionWithLinkSyntax(), "UTF-8"),
                    "");
            PostMethod httppost = new PostMethod(
                    url);
            Loggers.SERVER.info("SlackNotificationListener :: Preparing message for URL " + url);
			if (this.filename.length() > 0){
				File file = new File(this.filename);
			    httppost.setRequestEntity(new InputStreamRequestEntity(new FileInputStream(file)));
			    httppost.setContentChunked(true);
			}
			if (this.payload != null){
                //TODO: Set the body
                List<Attachment> attachments = new ArrayList<Attachment>();
                Attachment attachment = new Attachment(this.payload.getBuildResult(), null, null, this.payload.getColor());
                attachment.addField(this.payload.getBuildResult(), "Agent: " + this.payload.getAgentName(), false);

                StringBuilder sbCommits = new StringBuilder();

                List<Commit> commits = this.payload.getCommits();

                boolean truncated = false;
                int totalCommits = commits.size();
                if(commits.size() > 5){
                    commits = commits.subList(0, 5 > commits.size() ? commits.size() : 5);
                }

                for(Commit commit : commits){
                    String revision = commit.getRevision();
                    revision = revision == null ? "" : revision;
                    sbCommits.append(String.format("%s :: %s :: %s\n", revision.substring(0, Math.min(revision.length(), 10)), commit.getUserName(), commit.getDescription()));
                }

                if(truncated){
                    sbCommits.append(String.format("(+ %d more)\n", totalCommits -5));
                }

                if(!commits.isEmpty())
                {
                    attachment.addField("Commits", sbCommits.toString(), false);
                }

                attachments.add(attachment);

                String attachmentsParam = String.format("attachments=%s", URLEncoder.encode(convertAttachmentsToJson(attachments), "UTF-8"));

                Loggers.SERVER.info("SlackNotificationListener :: Body message will be " + attachmentsParam);

                httppost.setRequestEntity(new StringRequestEntity(attachmentsParam, CONTENT_TYPE, "UTF-8"));
			}
		    try {
		        client.executeMethod(httppost);
		        this.resultCode = httppost.getStatusCode();
                if(httppost.getResponseContentLength() > 0)
                {
                    this.content = httppost.getResponseBodyAsString();
                }
		    } finally {
		        httppost.releaseConnection();
		    }   
		}
	}

    public static String convertAttachmentsToJson(List<Attachment> attachments)
    {
        Gson gson = new Gson();
        return gson.toJson(attachments);
//        XStream xstream = new XStream(new JsonHierarchicalStreamDriver());
//        xstream.setMode(XStream.NO_REFERENCES);
//        xstream.alias("build", Attachment.class);
//        /* For some reason, the items are coming back as "@name" and "@value"
//         * so strip those out with a regex.
//         */
//        return xstream.toXML(attachments).replaceAll("\"@(fallback|text|pretext|color|fields|title|value|short)\": \"(.*)\"", "\"$1\": \"$2\"");
    }

    public Integer getStatus(){
		return this.resultCode;
	}
	
	public String getProxyHost() {
		return proxyHost;
	}

	public int getProxyPort() {
		return proxyPort;
	}

    public String getTeamName()
    {
        return teamName;
    }

    public void setTeamName(String teamName)
    {
        this.teamName = teamName;
    }

    public String getToken()
    {
        return token;
    }

    public void setToken(String token)
    {
        this.token = token;
    }

    public String getIconUrl()
    {
        return this.iconUrl;
    }

    public void setIconUrl(String iconUrl)
    {
        this.iconUrl = iconUrl;
    }

    public String getBotName()
    {
        return this.botName;
    }

    public void setBotName(String botName)
    {
        this.botName = botName;
    }

	public String getChannel() {
		return channel;
	}

	public void setChannel(String channel) {
		this.channel = channel;
	}
	
	public String getParameterisedUrl(){
        //TODO: Implement different url logic
		return this.channel +  this.parametersAsQueryString();
	}

	public String parametersAsQueryString(){
		String s = "";
		for (Iterator<NameValuePair> i = this.params.iterator(); i.hasNext();){
			NameValuePair nv = i.next();
			s += "&" + nv.getName() + "=" + nv.getValue(); 
		}
		if (s.length() > 0 ){
			return "?" + s.substring(1);
		}
		return s;
	}
	
	public void addParam(String key, String value){
		this.params.add(new NameValuePair(key, value));
	}

	public void addParams(List<NameValuePair> paramsList){
		for (Iterator<NameValuePair> i = paramsList.iterator(); i.hasNext();){
			this.params.add(i.next()); 
		}		
	}
	
	public String getParam(String key){
		for (Iterator<NameValuePair> i = this.params.iterator(); i.hasNext();){
			NameValuePair nv = i.next();
			if (nv.getName().equals(key)){
				return nv.getValue();
			}
		}		
		return "";
	}
	
	public void setFilename(String filename) {
		this.filename = filename;
	}

	public String getFilename() {
		return filename;
	}

	public String getContent() {
		return content;
	}

	public Boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(Boolean enabled) {
		this.enabled = enabled;
	}

	public void setEnabled(String enabled){
		if (enabled.toLowerCase().equals("true")){
			this.enabled = true;
		} else {
			this.enabled = false;
		}
	}

	public Boolean isErrored() {
		return errored;
	}

	public void setErrored(Boolean errored) {
		this.errored = errored;
	}

	public String getErrorReason() {
		return errorReason;
	}

	public void setErrorReason(String errorReason) {
		this.errorReason = errorReason;
	}

//	public Integer getEventListBitMask() {
//		return EventListBitMask;
//	}
//
//	public void setTriggerStateBitMask(Integer triggerStateBitMask) {
//		EventListBitMask = triggerStateBitMask;
//	}

	public String getProxyUsername() {
		return proxyUsername;
	}

	public void setProxyUsername(String proxyUsername) {
		this.proxyUsername = proxyUsername;
	}

	public String getProxyPassword() {
		return proxyPassword;
	}

	public void setProxyPassword(String proxyPassword) {
		this.proxyPassword = proxyPassword;
	}

	public SlackNotificationPayloadContent getPayload() {
		return payload;
	}

	public void setPayload(SlackNotificationPayloadContent payloadContent) {
		this.payload = payloadContent;
	}

	@Override
	public BuildState getBuildStates() {
		return states;
	}

	@Override
	public void setBuildStates(BuildState states) {
		this.states = states;
	}
}
