package per.dizzam.sustc.jwxt;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Map.Entry;

import org.apache.http.ParseException;
import org.apache.http.auth.AuthenticationException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonWriter;

import per.dizzam.sustc.cas.Method;
import per.dizzam.sustc.cas.NetworkConnection;

public class CourseData extends NetworkConnection {

	private Logger logger = Logger.getLogger("CourseCenter");

	JsonObject course;
	public JsonObject selected;

	private final File storage = new File("data.json");

	private ArrayList<String> id = new ArrayList<String>();
	/** 选课主页 */
	private String Xsxk = "/xsxk/xsxk_index?jx0502zbid=";
	private int index = 0;
	/** 选课中心主页 */
	private String xklc_list = "/xsxk/xklc_list";
	private boolean isChoose = false;

	/** 已选课程 */
	private final String Xkjglb = "/xsxkjg/comeXkjglb";

	/** 查询课程 */
	private final String query = "/xsxkkc/xsxk%s?kcxx=&skls=&skxq=&skjc=&sfym=false&sfct=false";

	public CourseData() {
		this("", "");
	}

	/**
	 * The constructor will load the cache file and initialize the necessary
	 * field
	 * 
	 * @param user
	 *            User name of CAS
	 * @param pass
	 *            Password of CSA
	 */
	public CourseData(String user, String pass) {
		course = new JsonObject();
		selected = new JsonObject();
		url = "http://jwxt.sustech.edu.cn/jsxsd";
		username = user;
		password = pass;
		try {
			loadData();
			logger.info("Load storage.");
		} catch (FileNotFoundException e) {
			logger.warn("Load Storage Failed: " + e.getMessage());
		} catch (IOException e) {
			logger.fatal(e.getMessage(), e);
			System.exit(-1);
		}
	}

	/**
	 * Call getIn using default index <br />
	 * if {@link #getIn(int)} has been called then the default index is set as
	 * the last calling, else the index is 0
	 * 
	 * @see #getIn(int)
	 * 
	 * @throws AuthenticationException
	 *             If identity is error
	 * @throws StatusException
	 *             When no selection is available
	 * @throws IOException
	 *             When network error occurs
	 */
	public void getIn() throws AuthenticationException, StatusException, IOException {
		getIn(index);
	}

	/**
	 * 进入选课主页
	 * 
	 * @param index
	 *            表明进入第几个选课列表
	 * @throws AuthenticationException
	 *             If identity is error
	 * @throws StatusException
	 *             When no selection is available
	 * @throws IOException
	 *             When network error occurs
	 */
	public void getIn(int index) throws AuthenticationException, StatusException, IOException {
		if (isLogin()) {
			getIndex();
			if (index >= id.size()) {
				throw new StatusException("No such selection open!");
			}
			CloseableHttpResponse response = dataFetcher(Method.GET, Xsxk + id.get(index));
			try {
				if (EntityUtils.toString(response.getEntity()).contains("不在选课时间范围内")) {
					throw new StatusException("尚未开放选课");
				}
				this.index = index;
			} catch (ParseException e) {
				logger.error(e.getMessage(), e);
			} finally {
				response.close();
			}
		} else {
			login();
			getIn(index);
		}
	}

	private void getIndex() throws AuthenticationException, IOException, StatusException {
		if (!isChoose) {
			CloseableHttpResponse response = dataFetcher(Method.GET, xklc_list);
			try {
				Document document = Jsoup.parse(EntityUtils.toString(response.getEntity()));
				Elements elements = document.getElementById("tbKxkc").getElementsByTag("a");
				if (elements.isEmpty()) {
					throw new StatusException("尚未开放选课");
				}
				for (Element element : elements) {
					id.add(element.attr("href").split("=")[1]);
				}
				isChoose = true;
			} catch (IndexOutOfBoundsException | NullPointerException e) {
				logger.error(e.getMessage(), e);
				throw new StatusException("未知错误", e);
			} finally {
				response.close();
			}
		}
	}

	/**
	 * @return the number of course selection available
	 * @throws AuthenticationException
	 *             If identity is error
	 * @throws IOException
	 *             When network error occurs
	 */
	public int getCoursesNumber() throws AuthenticationException, IOException {
		if (isChoose) {
			return id.size();
		} else {
			try {
				getIndex();
			} catch (StatusException e) {
				return 0;
			}
			return getCoursesNumber();
		}
	}

	/**
	 * 更新课程数据
	 * 
	 * @return the jsonObject contain the current course information
	 * @throws AuthenticationException
	 *             If identity is error
	 * @throws StatusException
	 *             When no selection is available
	 */
	public JsonObject updateCourseData() throws AuthenticationException, StatusException {
		try {
			getIn();
		} catch (IOException e) {
			logger.warn(
					String.format("Failed to update selected data: %s(%s)", e.getClass().getName(), e.getMessage()));
			return course;
		}
		for (CourseRepo repo : CourseRepo.values()) {
			course.add(repo.name(), updateCourseData1(repo));
		}
		return course;
	}

	private JsonElement updateCourseData1(CourseRepo repo) throws AuthenticationException {
		try {
			JsonParser parse;
			JsonObject source;
			// 获取总课程数
			CloseableHttpResponse response = dataFetcher(Method.POST, String.format(query, repo.name()),
					new String[] { "iDisplayStart=0", "iDisplayLength=0" });
			try {
				if (response.getStatusLine().getStatusCode() == 200) {
					parse = new JsonParser(); // 创建json解析器
					source = (JsonObject) parse.parse(new StringReader(EntityUtils.toString(response.getEntity()))); // 创建jsonObject对象
					// 获取全部课程并写入source
					CloseableHttpResponse response2 = dataFetcher(Method.POST, String.format(query, repo.name()),
							new String[] { "iDisplayStart=0",
									"iDisplayLength=" + source.get("iTotalRecords").getAsString() });
					try {
						source = (JsonObject) parse
								.parse(new StringReader(EntityUtils.toString(response2.getEntity()))); // 创建jsonObject对象
						return source.get("aaData").getAsJsonArray();
					} finally {
						response2.close();
					}
				} else {
					logger.warn(String.format("Failed to update %s, ignore it.", repo.getName()));
					return course.get(repo.name());
				}
			} finally {
				response.close();
			}
		} catch (ParseException | IOException | NullPointerException e) {
			logger.error(e.getMessage(), e);
		}
		return course.get(repo.name());
	}

	/**
	 * 更新已选课程数据
	 * 
	 * @return An jsonArray contains the id of the selected course
	 * @throws AuthenticationException
	 *             If identity is error
	 * @throws StatusException
	 *             When no selection is available
	 */
	public JsonObject updateSelected() throws AuthenticationException, StatusException {
		try {
			getIn();
		} catch (IOException e) {
			logger.warn(
					String.format("Failed to update selected data: %s(%s)", e.getClass().getName(), e.getMessage()));
			return selected;
		}
		try {
			CloseableHttpResponse response = dataFetcher(Method.GET, Xkjglb, null);
			try {
				String string = EntityUtils.toString(response.getEntity());
				Document document = Jsoup.parse(string);
				Elements courses = document.getElementsByTag("tbody").get(0).getElementsByTag("div");
				for (Entry<String, JsonElement> element : selected.entrySet()) {
					selected.addProperty(element.getKey(), false);
				}
				for (int i = 0; i < courses.size(); i++) {
					String id = courses.get(i).id().split("_")[1];
					boolean flag = false;
					for (Entry<String, JsonElement> element : selected.entrySet()) {
						if (element.getKey().equals(id)) {
							selected.addProperty(element.getKey(), false);
							flag = true;
							break;
						}
					}
					if (!flag) {
						selected.addProperty(id, true);
					}
				}
			} catch (ParseException | IOException | IndexOutOfBoundsException | NullPointerException e) {
				logger.error(e.getMessage(), e);
			} finally {
				try {
					response.close();
				} catch (IOException e) {
					logger.error(e.getMessage(), e);
				}
			}

		} catch (IOException e1) {
			logger.error(e1.getMessage(), e1);
		}
		return selected;
	}

	private void loadData() throws FileNotFoundException, IOException {
		if (storage.exists()) {
			FileReader reader = new FileReader(storage);
			JsonParser parse = new JsonParser(); // 创建json解析器
			JsonObject store = parse.parse(reader).getAsJsonObject();
			course = (JsonObject) store.get("courses");
			selected = (JsonObject) store.get("selected");
			reader.close();
		} else {
			throw new FileNotFoundException(String.format("Can't find '%s'", storage.getName()));
		}
	}

	public void saveToFile() throws IOException {
		if (!storage.exists()) {
			storage.createNewFile();
		}
		JsonObject store = new JsonObject();
		store.add("courses", course);
		store.add("selected", selected);
		FileWriter fileWritter = new FileWriter(storage, false);
		JsonWriter writer = new JsonWriter(fileWritter);
		writer.setLenient(true);
		writer.setIndent("  ");
		Streams.write(store, writer);
		writer.flush();
		writer.close();
	}

	public JsonObject getCourse() {
		return course;
	}

	public JsonObject getSelected() {
		return selected;
	}

	@Override
	public void login() throws AuthenticationException {
		login(username, password);
	}

	public void login(String user, String pass) throws AuthenticationException {
		username = user;
		password = pass;
		super.login();
	}

}
