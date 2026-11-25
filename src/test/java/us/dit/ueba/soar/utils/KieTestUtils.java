package us.dit.ueba.soar.utils;

import java.util.Map;

import org.kie.kogito.auth.IdentityProvider;
import org.kie.kogito.usertask.UserTaskService;
import org.kie.kogito.usertask.impl.lifecycle.DefaultUserTaskLifeCycle;
import org.kie.kogito.usertask.view.UserTaskView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class KieTestUtils {
    Logger logger = LoggerFactory.getLogger(KieTestUtils.class);
    @Autowired
    UserTaskService taskService;

    public void printTaskInfo(UserTaskView task) {
        logger.info("Task ID: " + task.getId());
        logger.info("Task Name: " + task.getTaskName());
        logger.info("Task Description: " + task.getTaskDescription());
        logger.info("Task Status: " + task.getStatus());
        logger.info("Task UserTaskId: " + task.getUserTaskId());
        logger.info("Task inputs\n");
        task.getInputs().forEach((key, value) -> logger.info("Input: " + key + " = " + value));
        logger.info("Task Actual Owner: " + task.getActualOwner());
        logger.info("Task Potential Groups: " + task.getPotentialGroups());
    }

    public void completeTask(UserTaskView task, Map<String, Object> inputs, IdentityProvider identityProvider) {

        taskService.transition(task.getId(), DefaultUserTaskLifeCycle.CLAIM, inputs, identityProvider);
        taskService.transition(task.getId(), DefaultUserTaskLifeCycle.COMPLETE, inputs, identityProvider);
    }

}
