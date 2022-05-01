package factoryBeanDemo;

import java.util.Objects;

import org.springframework.beans.factory.FactoryBean;

import factoryBeanDemo.Girl;

public class GirlFactoryBean implements FactoryBean<Girl> {
	private String nameAgeStr;

	@Override
	public Girl getObject() throws Exception {
		//获取参数
//		Objects.requireNonNull(nameAgeStr);
		String[] split = nameAgeStr.split(",");
		Girl p = new Girl();
		p.setAge(Integer.parseInt(split[0]));
		p.setName(split[1]);
		//这里可能还有其他复杂事情需要处理
		return p;
	}

	@Override
	public Class<?> getObjectType() {
		return Girl.class;
	}

	@Override
	public boolean isSingleton() {
		return false;
	}

	public String getNameAgeStr() {
		return nameAgeStr;
	}

	public void setNameAgeStr(String nameAgeStr) {
		this.nameAgeStr = nameAgeStr;
	}
}
