package circleDemo;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class CircleTest {
	public static void main(String[] args) {
		BeanFactory beanFactory = new ClassPathXmlApplicationContext("circleDemo.xml");
		//获取对应的对象化
		A a = beanFactory.getBean(A.class);
		System.out.println(a.getB());
		B b = beanFactory.getBean(B.class);
		System.out.println(b.getA());

	}
}
