cmake_minimum_required(VERSION 3.0.2)
project(iron_ore_robot_demo)

find_package(catkin REQUIRED COMPONENTS
  actionlib
  actionlib_msgs
  message_generation
  rospy
  std_msgs
)

add_message_files(
  FILES
  RobotSkill.msg
)

add_action_files(
  FILES
  ExecuteMission.action
)

generate_messages(
  DEPENDENCIES
  actionlib_msgs
  std_msgs
)

catkin_package(
  CATKIN_DEPENDS actionlib actionlib_msgs message_runtime rospy std_msgs
)

catkin_install_python(PROGRAMS
  scripts/robot_action_server.py
  scripts/robot_gateway.py
  DESTINATION ${CATKIN_PACKAGE_BIN_DESTINATION}
)

install(DIRECTORY launch
  DESTINATION ${CATKIN_PACKAGE_SHARE_DESTINATION}
)
