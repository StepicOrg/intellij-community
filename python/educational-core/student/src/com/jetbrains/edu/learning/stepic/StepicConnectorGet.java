/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.edu.learning.stepic;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.jetbrains.edu.learning.StudySerializationUtils;
import com.jetbrains.edu.learning.core.EduNames;
import com.jetbrains.edu.learning.courseFormat.Course;
import com.jetbrains.edu.learning.courseFormat.Lesson;
import com.jetbrains.edu.learning.courseFormat.Task;
import com.jetbrains.edu.learning.courseFormat.TaskFile;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class StepicConnectorGet {
  private static final Logger LOG = Logger.getInstance(StepicConnectorGet.class.getName());
  private static final String stepicUrl = "https://stepic.org/";
  public static final String PYCHARM_PREFIX = "pycharm";
  public static final String CODE_PREFIX = "code";
  public static final String PYTHON27 = "python27";
  public static final String PYTHON3 = "python3";

  static final private Gson GSON =
    new GsonBuilder().registerTypeAdapter(TaskFile.class, new StudySerializationUtils.Json.StepicTaskFileAdapter())
      .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();


  static <T> T getFromStepic(String link, final Class<T> container) throws IOException {
    final HttpGet request = new HttpGet(EduStepicNames.STEPIC_API_URL + link);

    final CloseableHttpResponse response = StepicConnectorLogin.getHttpClient().execute(request);
    final StatusLine statusLine = response.getStatusLine();
    final HttpEntity responseEntity = response.getEntity();
    final String responseString = responseEntity != null ? EntityUtils.toString(responseEntity) : "";
    if (statusLine.getStatusCode() != HttpStatus.SC_OK) {
      throw new IOException("Stepic returned non 200 status code " + responseString);
    }
    return GSON.fromJson(responseString, container);
  }

  @NotNull
  public static List<CourseInfo> getCourses() {
    try {
      List<CourseInfo> result = new ArrayList<CourseInfo>();
      int pageNumber = 1;
      while (addCoursesFromStepic(result, pageNumber)) {
        pageNumber += 1;
      }
      return result;
    }
    catch (IOException e) {
      LOG.error("Cannot load course list " + e.getMessage());
    }
    return Collections.singletonList(CourseInfo.INVALID_COURSE);
  }

  @NotNull
  public static CourseInfo getDefaultCourse() {
    CourseInfo courseInfo = null;
    try {
      courseInfo = getFromStepic(EduStepicNames.COURSES + "/217", StepicWrappers.CoursesContainer.class).courses.get(0);
    }
    catch (IOException e) {
      e.printStackTrace();
    }
    return courseInfo;
  }

  private static boolean addCoursesFromStepic(List<CourseInfo> result, int pageNumber) throws IOException {
    final String url = pageNumber == 0 ? EduStepicNames.COURSES : EduStepicNames.COURSES_FROM_PAGE + String.valueOf(pageNumber);
    final StepicWrappers.CoursesContainer coursesContainer = getFromStepic(url, StepicWrappers.CoursesContainer.class);
    final List<CourseInfo> courseInfos = coursesContainer.courses;
    for (CourseInfo info : courseInfos) {
      final String courseType = info.getType();
      if (!info.isAdaptive() && StringUtil.isEmptyOrSpaces(courseType)) continue;
      final List<String> typeLanguage = StringUtil.split(courseType, " ");
      // TODO: should adaptive course be of PyCharmType ?
      if (info.isAdaptive() || (typeLanguage.size() == 2 && PYCHARM_PREFIX.equals(typeLanguage.get(0)))) {
        for (Integer instructor : info.instructors) {
          final StepicUser author =
            getFromStepic(EduStepicNames.USERS + "/" + String.valueOf(instructor), StepicWrappers.AuthorWrapper.class).users.get(0);
          info.addAuthor(author);
        }

        String name = info.getName().replaceAll("[^a-zA-Z0-9\\s]", "");
        info.setName(name.trim());

        result.add(info);
      }
    }
    return coursesContainer.meta.containsKey("has_next") && coursesContainer.meta.get("has_next") == Boolean.TRUE;
  }

  public static Course getCourse(@NotNull final Project project, @NotNull final CourseInfo info) {
    Course course = new Course();
    course.setAuthors(info.getAuthors());
    course.setDescription(info.getDescription());
    course.setAdaptive(info.isAdaptive());
    course.setId(info.id);
    course.setUpToDate(true);  // TODO: get from stepic

    if (course.isAdaptive()) {
      course = getAdaptiveCourse(project, course, info);
    }
    else {
      course = getRegularCourse(project, course, info);
    }
    return course;
  }

  private static Course getRegularCourse(@NotNull final Project project, Course course, @NotNull final CourseInfo info) {
    String courseType = info.getType();
    course.setName(info.getName());
    course.setCourseType(info.getType().startsWith("pycharm") ? info.getType() : "stepic");

    //    what for?
    course.setLanguage(courseType.substring(PYCHARM_PREFIX.length() + 1));
    try {
      for (Integer section : info.sections) {
        switch (course.getCourseType()){
          case ("stepic") : course.addLessons(getLessons2(section));
            break;
          default:
            course.addLessons(getLessons(section));
        }
      }
      return course;
    }
    catch (IOException e) {
      LOG.error("IOException " + e.getMessage());
      return null;
    }
  }

  private static Course getAdaptiveCourse(@NotNull final Project project, Course course, @NotNull final CourseInfo info) {
    final Lesson lesson = new Lesson();
    course.setName(info.getName());
    //TODO: more specific name?
    lesson.setName("Adaptive");
    course.addLesson(lesson);
    final Task recommendation = EduAdaptiveStepicConnector.getNextRecommendation(project, course);
    if (recommendation != null) {
      lesson.addTask(recommendation);
      return course;
    }
    else {
      return null;
    }
  }

  public static List<Lesson> getLessons(int sectionId) throws IOException {
    final StepicWrappers.SectionContainer
      sectionContainer = getFromStepic(EduStepicNames.SECTIONS + String.valueOf(sectionId), StepicWrappers.SectionContainer.class);
    List<Integer> unitIds = sectionContainer.sections.get(0).units;

    StepicWrappers.UnitContainer
      unitContainer = getFromStepic(EduStepicNames.UNITS + "/" + getIdQuery(unitIds), StepicWrappers.UnitContainer.class);
    List<Integer> lessonsIds = new ArrayList<>();
    unitContainer.units.forEach(x -> lessonsIds.add(x.lesson));
    StepicWrappers.LessonContainer
      lessonContainer = getFromStepic(EduStepicNames.LESSONS + getIdQuery(lessonsIds), StepicWrappers.LessonContainer.class);

    final List<Lesson> lessons = new ArrayList<Lesson>();
    for (Lesson lesson : lessonContainer.lessons) {
      createTasks(lesson, lesson.steps);
      if (!lesson.taskList.isEmpty()) {
        lessons.add(lesson);
      }
    }

    return lessons;
  }

  public static List<Lesson> getLessons2(int sectionId) throws IOException {
    final StepicWrappers.SectionContainer
      sectionContainer = getFromStepic(EduStepicNames.SECTIONS + String.valueOf(sectionId), StepicWrappers.SectionContainer.class);
    List<Integer> unitIds = sectionContainer.sections.get(0).units;

    StepicWrappers.UnitContainer
      unitContainer = getFromStepic(EduStepicNames.UNITS + "/" + getIdQuery(unitIds), StepicWrappers.UnitContainer.class);
    List<Integer> lessonsIds = new ArrayList<>();
    unitContainer.units.forEach(x -> lessonsIds.add(x.lesson));
    StepicWrappers.LessonContainer
      lessonContainer = getFromStepic(EduStepicNames.LESSONS + getIdQuery(lessonsIds), StepicWrappers.LessonContainer.class);

    String sectionName = sectionContainer.sections.get(0).title;
    final List<Lesson> lessons = new ArrayList<Lesson>();
    for (Lesson lesson : lessonContainer.lessons) {
      lesson.setName(sectionName + EduNames.SEPARATOR + lesson.getName());
      //      LOG.info("set lesson name " + lesson.getName());
      createTasks(lesson, lesson.steps);
      if (!lesson.taskList.isEmpty()) {
        lessons.add(lesson);
      }
    }
    return lessons;
  }

  private static void createTasks(Lesson lesson, List<Integer> stepicIds) throws IOException {
    final StepicWrappers.StepContainer stepContainer = getSteps(stepicIds);
    List<StepicWrappers.Step> steps = new ArrayList<>();
    stepContainer.steps.forEach(x -> steps.add(x.block));
    int i = 0;
    for (StepicWrappers.Step step : steps) {
      if (supported(step.name)) {
        final Task task = new Task();
        task.setStepicId(stepicIds.get(i++));

        switch (step.name) {
          case (CODE_PREFIX):
            createCodeTask(task, step);
            break;
          case (PYCHARM_PREFIX):
            createPyCharmTask(task, step);
            break;
        }
        lesson.taskList.add(task);
      }
    }
  }

  private static boolean supported(String name) {
    return CODE_PREFIX.equals(name) || PYCHARM_PREFIX.equals(name);
  }

  private static void createPyCharmTask(Task task, StepicWrappers.Step step) {
    task.setName(step.options != null ? step.options.title : PYCHARM_PREFIX);
    task.setText(step.text);
    for (StepicWrappers.TestFileWrapper wrapper : step.options.test) {
      task.addTestsTexts(wrapper.name, wrapper.text);
    }

    task.taskFiles = new HashMap<String, TaskFile>();      // TODO: it looks like we don't need taskFiles as map anymore
    if (step.options.files != null) {
      for (TaskFile taskFile : step.options.files) {
        task.taskFiles.put(taskFile.name, taskFile);
      }
    }
  }

  private static void createCodeTask(Task task, StepicWrappers.Step step) {
    task.setName("step" + task.getStepicId());
    if (step.options.samples != null) {
      final StringBuilder builder = new StringBuilder();
      for (List<String> sample : step.options.samples) {
        if (sample.size() == 2) {
          builder.append("<b>Sample Input:</b><br>");
          builder.append(StringUtil.replace(sample.get(0), "\n", "<br>"));
          builder.append("<br>");
          builder.append("<b>Sample Output:</b><br>");
          builder.append(StringUtil.replace(sample.get(1), "\n", "<br>"));
          builder.append("<br><br>");
        }
      }
      task.setText(step.text + "<br>" + builder.toString());
    }

    if (step.options.executionMemoryLimit != null && step.options.executionTimeLimit != null) {
      String builder = "<b>Memory limit</b>: " +
                       step.options.executionMemoryLimit + " Mb" +
                       "<br>" +
                       "<b>Time limit</b>: " +
                       step.options.executionTimeLimit + "s" +
                       "<br><br>";
      task.setText(task.getText() + builder);
    }

    final TaskFile taskFile = new TaskFile();
    taskFile.name = "Main.java";
    //final String templateForTask = getCodeTemplateForTask();
    final String templateForTask = step.options.codeTemplates.getTemplateForLanguage("java");
    taskFile.text = templateForTask == null ? "# write your answer here \n" : templateForTask;
    task.taskFiles.put(taskFile.name, taskFile);
  }

  public static StepicWrappers.StepContainer getSteps(List<Integer> steps) throws IOException {
    return getFromStepic(EduStepicNames.STEPS + "/" + getIdQuery(steps), StepicWrappers.StepContainer.class);
  }

  public static StepicWrappers.AuthorWrapper getCurrentUser() {
    try {
      return getFromStepic(EduStepicNames.CURRENT_USER, StepicWrappers.AuthorWrapper.class);
    }
    catch (IOException e) {
      LOG.warn("Couldn't get author info");
    }
    return null;
  }

  private static String getIdQuery(List<Integer> list) {
    StringBuilder sb = new StringBuilder();
    sb.append("?");
    for (Integer id : list) {
      sb.append("ids[]=" + id + "&");
    }
    return sb.toString();
  }
}
