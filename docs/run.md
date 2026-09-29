## 启动后端

cd backend

确认java是21：
java --version

vscode里面的终端改成git bash

然后添加环境变量:

export DB_URL='jdbc:mysql://10.42.0.1:3306/cendo?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai'
export DB_USER='cendo'
export DB_PASSWORD='cendo'
export REDIS_HOST='10.42.0.1'
export REDIS_PORT='6379'

运行后端:
mvn spring-boot:run

## 启动前端

cd frontend

npm install

npm run dev
