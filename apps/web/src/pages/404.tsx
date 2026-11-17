import { Link } from '@umijs/max';
import { Button, Result } from 'antd';

/**
 * 404 兜底页。
 *
 * 使用 antd Result 提供可读的错误说明与返回入口，避免直接展示空白页。
 *
 * @returns 404 结果页
 */
export default function NotFoundPage() {
  return (
    <Result
      extra={
        <Link to="/chat">
          <Button type="primary">返回工作空间</Button>
        </Link>
      }
      status="404"
      subTitle="该地址不存在，或对应模块尚未交付。"
      title="页面不存在"
    />
  );
}
