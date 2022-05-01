package factoryBeanDemo;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import factoryBeanDemo.Girl;

public class FactoryBeanTest {
	public static void main(String[] args) {
		// 1，这个是BeanFactory
		BeanFactory beanFactory = new ClassPathXmlApplicationContext("beans.xml");
		//获取对应的对象化
		Object demo = beanFactory.getBean("girl");
		System.out.println(demo instanceof Girl);
		System.out.println(demo);

		// 2.1 从FactoryBean获取对象
		Girl girlFromFB = beanFactory.getBean("girlFromFB", Girl.class);
		System.out.println(girlFromFB);
		// 2.2 获取GirlFactoryBean本身
		Object bean = beanFactory.getBean("&girlFromFB");
		System.out.println(bean instanceof GirlFactoryBean);
		GirlFactoryBean factoryBean=(GirlFactoryBean) bean;
		System.out.println("初始化参数为："+factoryBean.getNameAgeStr());
	}
}
